/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.jdbc;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.InsertSetMoreStep;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record5;
import org.jooq.SelectConditionStep;
import org.jooq.SelectOffsetStep;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.JDBCUtils;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.BinaryAnnotation.Type;
import zipkin.DependencyLink;
import zipkin.Endpoint;
import zipkin.InMemorySpanStore;
import zipkin.QueryRequest;
import zipkin.Span;
import zipkin.SpanStore;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.internal.CorrectForClockSkew;
import zipkin.internal.DependencyLinkSpan;
import zipkin.internal.DependencyLinker;
import zipkin.internal.Nullable;
import zipkin.internal.Pair;
import zipkin.jdbc.internal.generated.tables.ZipkinAnnotations;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;
import static zipkin.BinaryAnnotation.Type.STRING;
import static zipkin.Constants.CLIENT_ADDR;
import static zipkin.Constants.SERVER_ADDR;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.internal.Util.checkNotNull;
import static zipkin.jdbc.internal.generated.tables.ZipkinAnnotations.ZIPKIN_ANNOTATIONS;
import static zipkin.jdbc.internal.generated.tables.ZipkinSpans.ZIPKIN_SPANS;

public final class JDBCSpanStore implements SpanStore {
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  static {
    System.setProperty("org.jooq.no-logo", "true");
  }

  private final DataSource datasource;
  private final Settings settings;
  private final ExecuteListenerProvider listenerProvider;
  // TODO: Add retention
  private final InMemorySpanStore cache = new InMemorySpanStore();

  public JDBCSpanStore(DataSource datasource, Settings settings, @Nullable ExecuteListenerProvider listenerProvider) {
    this.datasource = checkNotNull(datasource, "datasource");
    this.settings = checkNotNull(settings, "settings");
    this.listenerProvider = listenerProvider;
  }

  void clear() throws SQLException {
    try (Connection conn = datasource.getConnection()) {
      context(conn).truncate(ZIPKIN_SPANS).execute();
      context(conn).truncate(ZIPKIN_ANNOTATIONS).execute();
    }
  }

  @Override
  public void accept(Iterator<Span> spansIterator) {
    if (!spansIterator.hasNext()) return;
    Collection<Span> iteratorCache = new ArrayList<>();
    while (spansIterator.hasNext()) iteratorCache.add(spansIterator.next());
    cache.accept(iteratorCache.iterator());
    try (Connection conn = datasource.getConnection()) {
      DSLContext create = context(conn);

      List<Query> inserts = new ArrayList<>();
      Iterator<Span> spans = iteratorCache.iterator();
      while (spans.hasNext()) {
        Span span = ApplyTimestampAndDuration.apply(spans.next());
        Long binaryAnnotationTimestamp = span.timestamp;
        if (binaryAnnotationTimestamp == null) { // fallback if we have no timestamp, yet
          binaryAnnotationTimestamp = System.currentTimeMillis() * 1000;
        }

        Map<TableField<Record, ?>, Object> updateFields = new LinkedHashMap<>();
        if (!span.name.equals("") && !span.name.equals("unknown")) {
          updateFields.put(ZIPKIN_SPANS.NAME, span.name);
        }
        if (span.timestamp != null) {
          updateFields.put(ZIPKIN_SPANS.START_TS, span.timestamp);
        }

        if (span.duration != null) {
          updateFields.put(ZIPKIN_SPANS.DURATION, getDurationFromCacheIfPresent(span));
        }


        InsertSetMoreStep<Record> insertSpan = create.insertInto(ZIPKIN_SPANS)
            .set(ZIPKIN_SPANS.TRACE_ID, span.traceId)
            .set(ZIPKIN_SPANS.ID, span.id)
            .set(ZIPKIN_SPANS.PARENT_ID, span.parentId)
            .set(ZIPKIN_SPANS.NAME, span.name)
            .set(ZIPKIN_SPANS.DEBUG, span.debug)
            .set(ZIPKIN_SPANS.START_TS, span.timestamp)
            .set(ZIPKIN_SPANS.DURATION, span.duration);

        inserts.add(updateFields.isEmpty() ?
            insertSpan.onDuplicateKeyIgnore() :
            insertSpan.onDuplicateKeyUpdate().set(updateFields));

        for (Annotation annotation : span.annotations) {
          InsertSetMoreStep<Record> insert = create.insertInto(ZIPKIN_ANNOTATIONS)
              .set(ZIPKIN_ANNOTATIONS.TRACE_ID, span.traceId)
              .set(ZIPKIN_ANNOTATIONS.SPAN_ID, span.id)
              .set(ZIPKIN_ANNOTATIONS.A_KEY, annotation.value)
              .set(ZIPKIN_ANNOTATIONS.A_TYPE, -1)
              .set(ZIPKIN_ANNOTATIONS.A_TIMESTAMP, annotation.timestamp);
          if (annotation.endpoint != null) {
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME, annotation.endpoint.serviceName);
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_IPV4, annotation.endpoint.ipv4);
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_PORT, annotation.endpoint.port);
          }
          inserts.add(insert.onDuplicateKeyIgnore());
        }

        for (BinaryAnnotation annotation : span.binaryAnnotations) {
          InsertSetMoreStep<Record> insert = create.insertInto(ZIPKIN_ANNOTATIONS)
              .set(ZIPKIN_ANNOTATIONS.TRACE_ID, span.traceId)
              .set(ZIPKIN_ANNOTATIONS.SPAN_ID, span.id)
              .set(ZIPKIN_ANNOTATIONS.A_KEY, annotation.key)
              .set(ZIPKIN_ANNOTATIONS.A_VALUE, annotation.value)
              .set(ZIPKIN_ANNOTATIONS.A_TYPE, annotation.type.value)
              .set(ZIPKIN_ANNOTATIONS.A_TIMESTAMP, binaryAnnotationTimestamp);
          if (annotation.endpoint != null) {
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME, annotation.endpoint.serviceName);
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_IPV4, annotation.endpoint.ipv4);
            insert.set(ZIPKIN_ANNOTATIONS.ENDPOINT_PORT, annotation.endpoint.port);
          }
          inserts.add(insert.onDuplicateKeyIgnore());
        }
      }
      create.batch(inserts).execute();
    } catch (SQLException e) {
      throw new RuntimeException(e); // TODO
    }
  }

  private long getDurationFromCacheIfPresent(Span span) {
    List<List<Span>> cachedSpans = cache
        .getTracesByIds(Collections.singletonList(span.traceId));
    if (cachedSpans.size() == 1) {
      List<Span> spansForTraceId = cachedSpans.get(0);
      Optional<Span> spanWithSameId = spansForTraceId.stream()
          .filter(s -> s.id == span.id).findFirst();
      if (spanWithSameId.isPresent()) {
        // this duration will be the merged one
        return spanWithSameId.get().duration;
      }
    }
    return span.duration;
  }

  List<List<Span>> getTraces(@Nullable QueryRequest request, @Nullable Collection<Long> traceIds) {
    final Map<Long, List<Span>> spansWithoutAnnotations;
    final Map<Pair<?>, List<Record>> dbAnnotations;
    try (Connection conn = datasource.getConnection()) {
      if (request != null) {
        traceIds = toTraceIdQuery(context(conn), request).fetch(ZIPKIN_SPANS.TRACE_ID);
      }
      spansWithoutAnnotations = context(conn)
          .selectFrom(ZIPKIN_SPANS).where(ZIPKIN_SPANS.TRACE_ID.in(traceIds))
          .stream()
          .map(r -> new Span.Builder()
              .traceId(r.getValue(ZIPKIN_SPANS.TRACE_ID))
              .name(r.getValue(ZIPKIN_SPANS.NAME))
              .id(r.getValue(ZIPKIN_SPANS.ID))
              .parentId(r.getValue(ZIPKIN_SPANS.PARENT_ID))
              .timestamp(r.getValue(ZIPKIN_SPANS.START_TS))
              .duration(r.getValue(ZIPKIN_SPANS.DURATION))
              .debug(r.getValue(ZIPKIN_SPANS.DEBUG))
              .build())
          .collect(groupingBy((Span s) -> s.traceId, LinkedHashMap::new, Collectors.<Span>toList()));

      dbAnnotations = context(conn)
          .selectFrom(ZIPKIN_ANNOTATIONS)
          .where(ZIPKIN_ANNOTATIONS.TRACE_ID.in(spansWithoutAnnotations.keySet()))
          .orderBy(ZIPKIN_ANNOTATIONS.A_TIMESTAMP.asc(), ZIPKIN_ANNOTATIONS.A_KEY.asc())
          .stream()
          .collect(groupingBy((Record a) -> Pair.create(
              a.getValue(ZIPKIN_ANNOTATIONS.TRACE_ID),
              a.getValue(ZIPKIN_ANNOTATIONS.SPAN_ID)
          ), LinkedHashMap::new, Collectors.<Record>toList())); // LinkedHashMap preserves order while grouping
    } catch (SQLException e) {
      throw new RuntimeException("Error querying for " + request + ": " + e.getMessage());
    }

    List<List<Span>> result = new ArrayList<>(spansWithoutAnnotations.keySet().size());
    for (List<Span> spans : spansWithoutAnnotations.values()) {
      List<Span> trace = new ArrayList<>(spans.size());
      for (Span s : spans) {
        Span.Builder span = new Span.Builder(s);
        Pair<?> key = Pair.create(s.traceId, s.id);

        if (dbAnnotations.containsKey(key)) {
          for (Record a : dbAnnotations.get(key)) {
            Endpoint endpoint = endpoint(a);
            int type = a.getValue(ZIPKIN_ANNOTATIONS.A_TYPE);
            if (type == -1) {
              span.addAnnotation(Annotation.create(
                  a.getValue(ZIPKIN_ANNOTATIONS.A_TIMESTAMP),
                  a.getValue(ZIPKIN_ANNOTATIONS.A_KEY),
                  endpoint));
            } else {
              span.addBinaryAnnotation(BinaryAnnotation.create(
                  a.getValue(ZIPKIN_ANNOTATIONS.A_KEY),
                  a.getValue(ZIPKIN_ANNOTATIONS.A_VALUE),
                  Type.fromValue(type),
                  endpoint));
            }
          }
        }
        trace.add(span.build());
      }
      cache.accept(trace.iterator());
      trace = CorrectForClockSkew.apply(trace);
      result.add(trace);
    }
    Collections.sort(result, (left, right) -> right.get(0).compareTo(left.get(0)));
    return result;
  }

  @Override
  public List<List<Span>> getTraces(QueryRequest request) {
    return getTraces(request, null);
  }

  private DSLContext context(Connection conn) {
    return DSL.using(new DefaultConfiguration()
        .set(conn)
        .set(JDBCUtils.dialect(conn))
        .set(settings)
        .set(listenerProvider));
  }

  @Override
  public List<List<Span>> getTracesByIds(Collection<Long> traceIds) {
    return traceIds.isEmpty() ? emptyList() : getTraces(null, traceIds);
  }

  @Override
  public List<String> getServiceNames() {
    try (Connection conn = datasource.getConnection()) {
      return context(conn)
          .selectDistinct(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME)
          .from(ZIPKIN_ANNOTATIONS)
          .where(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME.isNotNull()
              .and(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME.ne("")))
          .fetch(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME);
    } catch (SQLException e) {
      throw new RuntimeException("Error querying for " + e + ": " + e.getMessage());
    }
  }

  @Override
  public List<String> getSpanNames(String serviceName) {
    if (serviceName == null) return emptyList();
    serviceName = serviceName.toLowerCase(); // service names are always lowercase!
    try (Connection conn = datasource.getConnection()) {
      return context(conn)
          .selectDistinct(ZIPKIN_SPANS.NAME)
          .from(ZIPKIN_SPANS)
          .join(ZIPKIN_ANNOTATIONS)
          .on(ZIPKIN_SPANS.TRACE_ID.eq(ZIPKIN_ANNOTATIONS.TRACE_ID))
          .and(ZIPKIN_SPANS.ID.eq(ZIPKIN_ANNOTATIONS.SPAN_ID))
          .where(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME.eq(serviceName))
          .orderBy(ZIPKIN_SPANS.NAME)
          .fetch(ZIPKIN_SPANS.NAME);
    } catch (SQLException e) {
      throw new RuntimeException("Error querying for " + serviceName + ": " + e.getMessage());
    }
  }

  @Override
  public List<DependencyLink> getDependencies(long endTs, @Nullable Long lookback) {
    endTs = endTs * 1000;
    try (Connection conn = datasource.getConnection()) {
      // Lazy fetching the cursor prevents us from buffering the whole dataset in memory.
      Cursor<Record5<Long, Long, Long, String, String>> cursor = context(conn)
          .selectDistinct(ZIPKIN_SPANS.TRACE_ID, ZIPKIN_SPANS.PARENT_ID, ZIPKIN_SPANS.ID,
              ZIPKIN_ANNOTATIONS.A_KEY, ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME)
          // left joining allows us to keep a mapping of all span ids, not just ones that have
          // special annotations. We need all span ids to reconstruct the trace tree. We need
          // the whole trace tree so that we can accurately skip local spans.
          .from(ZIPKIN_SPANS.leftJoin(ZIPKIN_ANNOTATIONS)
              .on(ZIPKIN_SPANS.TRACE_ID.eq(ZIPKIN_ANNOTATIONS.TRACE_ID).and(
                  ZIPKIN_SPANS.ID.eq(ZIPKIN_ANNOTATIONS.SPAN_ID)))
              .and(ZIPKIN_ANNOTATIONS.A_KEY.in(CLIENT_ADDR, SERVER_RECV, SERVER_ADDR)))
          .where(lookback == null ?
              ZIPKIN_SPANS.START_TS.lessOrEqual(endTs) :
              ZIPKIN_SPANS.START_TS.between(endTs - lookback * 1000, endTs))
          // Grouping so that later code knows when a span or trace is finished.
          .groupBy(ZIPKIN_SPANS.TRACE_ID, ZIPKIN_SPANS.ID, ZIPKIN_ANNOTATIONS.A_KEY).fetchLazy();

      Iterator<Iterator<DependencyLinkSpan>> traces =
          new DependencyLinkSpanIterator.ByTraceId(cursor.iterator());

      if (!traces.hasNext()) return Collections.emptyList();

      DependencyLinker linker = new DependencyLinker();

      while (traces.hasNext()) {
        linker.putTrace(traces.next());
      }

      return linker.link();
    } catch (SQLException e) {
      throw new RuntimeException("Error querying dependencies for endTs " + endTs + " and lookback " + lookback + ": " + e.getMessage());
    }
  }

  private static Endpoint endpoint(Record a) {
    String serviceName = a.getValue(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME);
    if (serviceName == null) {
      return null;
    }
    Short port = a.getValue(ZIPKIN_ANNOTATIONS.ENDPOINT_PORT);
    return port != null ?
        Endpoint.create(serviceName, a.getValue(ZIPKIN_ANNOTATIONS.ENDPOINT_IPV4), port.intValue())
        : Endpoint.create(serviceName, a.getValue(ZIPKIN_ANNOTATIONS.ENDPOINT_IPV4));
  }

  private static SelectOffsetStep<Record1<Long>> toTraceIdQuery(DSLContext context, QueryRequest request) {
    long endTs = (request.endTs > 0 && request.endTs != Long.MAX_VALUE) ? request.endTs * 1000
        : System.currentTimeMillis() * 1000;

    Table<?> table = ZIPKIN_SPANS.join(ZIPKIN_ANNOTATIONS)
        .on(ZIPKIN_SPANS.TRACE_ID.eq(ZIPKIN_ANNOTATIONS.TRACE_ID).and(
            ZIPKIN_SPANS.ID.eq(ZIPKIN_ANNOTATIONS.SPAN_ID)));

    Map<String, ZipkinAnnotations> keyToTables = new LinkedHashMap<>();
    int i = 0;
    for (String key : request.binaryAnnotations.keySet()) {
      keyToTables.put(key, ZIPKIN_ANNOTATIONS.as("a" + i++));
      table = join(table, keyToTables.get(key), key, STRING.value);
    }

    for (String key : request.annotations) {
      keyToTables.put(key, ZIPKIN_ANNOTATIONS.as("a" + i++));
      table = join(table, keyToTables.get(key), key, -1);
    }

    SelectConditionStep<Record1<Long>> dsl = context.selectDistinct(ZIPKIN_SPANS.TRACE_ID)
        .from(table)
        .where(ZIPKIN_ANNOTATIONS.ENDPOINT_SERVICE_NAME.eq(request.serviceName))
        .and(ZIPKIN_SPANS.START_TS.between(endTs - request.lookback * 1000, endTs));

    if (request.spanName != null) {
      dsl.and(ZIPKIN_SPANS.NAME.eq(request.spanName));
    }

    if (request.minDuration != null && request.maxDuration != null) {
      dsl.and(ZIPKIN_SPANS.DURATION.between(request.minDuration, request.maxDuration));
    } else if (request.minDuration != null){
      dsl.and(ZIPKIN_SPANS.DURATION.greaterOrEqual(request.minDuration));
    }

    for (Map.Entry<String, String> entry : request.binaryAnnotations.entrySet()) {
      dsl.and(keyToTables.get(entry.getKey()).A_VALUE.eq(entry.getValue().getBytes(UTF_8)));
    }
    return dsl.orderBy(ZIPKIN_SPANS.START_TS.desc()).limit(request.limit);
  }

  private static Table<?> join(Table<?> table, ZipkinAnnotations joinTable, String key, int type) {
    return table.join(joinTable)
        .on(ZIPKIN_SPANS.TRACE_ID.eq(joinTable.TRACE_ID))
        .and(ZIPKIN_SPANS.ID.eq(joinTable.SPAN_ID))
        .and(joinTable.A_TYPE.eq(type))
        .and(joinTable.A_KEY.eq(key));
  }
}
