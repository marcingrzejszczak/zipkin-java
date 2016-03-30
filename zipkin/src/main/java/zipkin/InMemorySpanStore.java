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
package zipkin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import zipkin.async.AsyncSpanConsumer;
import zipkin.async.AsyncSpanStore;
import zipkin.async.Callback;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.internal.CorrectForClockSkew;
import zipkin.internal.DependencyLinkSpan;
import zipkin.internal.DependencyLinker;
import zipkin.internal.MergeById;
import zipkin.internal.Nullable;
import zipkin.internal.Pair;
import zipkin.internal.Util;

import static zipkin.internal.Util.UTF_8;
import static zipkin.internal.Util.sortedList;

/**
 * This implements both {@link SpanStore} and {@link AsyncSpanStore} for convenience.
 *
 * <p>All {@link Callback callbacks} execute on the calling thread.
 */
public final class InMemorySpanStore implements SpanStore, AsyncSpanStore, AsyncSpanConsumer {
  private final Multimap<Long, Span> traceIdToSpans = new LinkedListMultimap<>();
  private final Multimap<String, Pair<Long>> serviceToTraceIdTimeStamp = new SortedByValue2Descending<>();
  private final Multimap<String, String> serviceToSpanNames = new LinkedHashSetMultimap<>();
  private int acceptedSpanCount;

  public synchronized void accept(List<Span> spans) {
    for (Span span : spans) {
      span = ApplyTimestampAndDuration.apply(span);
      Pair<Long> traceIdTimeStamp =
          Pair.create(span.traceId, span.timestamp == null ? Long.MIN_VALUE : span.timestamp);
      String spanName = span.name;
      traceIdToSpans.put(span.traceId, span);
      acceptedSpanCount++;

      for (String serviceName : span.serviceNames()) {
        serviceToTraceIdTimeStamp.put(serviceName, traceIdTimeStamp);
        serviceToSpanNames.put(serviceName, spanName);
      }
    }
  }

  public synchronized int acceptedSpanCount() {
    return acceptedSpanCount;
  }

  @Override public void accept(List<Span> spans, Callback<Void> callback) {
    try {
      accept(spans);
      callback.onSuccess(null);
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  public synchronized List<Long> traceIds() {
    return Util.sortedList(traceIdToSpans.keySet());
  }

  public synchronized void clear() {
    traceIdToSpans.clear();
    serviceToTraceIdTimeStamp.clear();
  }

  @Override
  public synchronized List<List<Span>> getTraces(QueryRequest request) {
    Set<Long> traceIds = traceIdsDescendingByTimestamp(request.serviceName);
    if (traceIds == null || traceIds.isEmpty()) return Collections.emptyList();

    List<List<Span>> result = new ArrayList<>(traceIds.size());
    for (long traceId : traceIds) {
      List<Span> next = getTrace(traceId);
      if (next != null && test(request, next)) {
        result.add(next);
      }
      if (result.size() == request.limit) {
        break;
      }
    }
    Collections.sort(result, TRACE_DESCENDING);
    return result;
  }

  Set<Long> traceIdsDescendingByTimestamp(String serviceName) {
    Collection<Pair<Long>> traceIdTimestamps = serviceToTraceIdTimeStamp.get(serviceName);
    if (traceIdTimestamps == null || traceIdTimestamps.isEmpty()) return Collections.emptySet();
    Set<Long> result = new LinkedHashSet<>();
    for (Pair<Long> traceIdTimestamp : traceIdTimestamps) {
      result.add(traceIdTimestamp._1);
    }
    return result;
  }

  static final Comparator<List<Span>> TRACE_DESCENDING = new Comparator<List<Span>>() {
    @Override
    public int compare(List<Span> left, List<Span> right) {
      return right.get(0).compareTo(left.get(0));
    }
  };

  @Override public void getTraces(QueryRequest request, Callback<List<List<Span>>> callback) {
    try {
      callback.onSuccess(getTraces(request));
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  @Override
  public synchronized List<Span> getTrace(long traceId) {
    List<Span> spans = getRawTrace(traceId);
    return spans == null ? null : CorrectForClockSkew.apply(MergeById.apply(spans));
  }

  @Override public void getTrace(long id, Callback<List<Span>> callback) {
    try {
      callback.onSuccess(getTrace(id));
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  @Override
  public synchronized List<Span> getRawTrace(long traceId) {
    List<Span> spans = (List<Span>) traceIdToSpans.get(traceId);
    if (spans == null || spans.isEmpty()) return null;
    return spans;
  }

  @Override public void getRawTrace(long traceId, Callback<List<Span>> callback) {
    try {
      callback.onSuccess(getRawTrace(traceId));
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  @Override
  public synchronized List<String> getServiceNames() {
    return sortedList(serviceToTraceIdTimeStamp.keySet());
  }

  @Override public void getServiceNames(Callback<List<String>> callback) {
    try {
      callback.onSuccess(getServiceNames());
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  @Override
  public synchronized List<String> getSpanNames(String service) {
    if (service == null) return Collections.emptyList();
    service = service.toLowerCase(); // service names are always lowercase!
    return sortedList(serviceToSpanNames.get(service));
  }

  @Override public void getSpanNames(String serviceName, Callback<List<String>> callback) {
    try {
      callback.onSuccess(getSpanNames(serviceName));
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  @Override
  public List<DependencyLink> getDependencies(long endTs, @Nullable Long lookback) {
    endTs *= 1000;
    if (lookback == null) {
      lookback = endTs;
    } else {
      lookback *= 1000;
    }

    DependencyLinker linksBuilder = new DependencyLinker();

    for (Collection<Span> trace : traceIdToSpans.delegate.values()) {
      if (trace.isEmpty()) continue;
      Collection<Span> mergedSpans = CorrectForClockSkew.apply(MergeById.apply(trace));
      List<DependencyLinkSpan> linkSpans = new LinkedList<>();
      for (Span s : mergedSpans) {
        Long timestamp = s.timestamp;
        if (timestamp == null ||
            timestamp < (endTs - lookback) ||
            timestamp > endTs) {
          continue;
        }
        DependencyLinkSpan.Builder linkSpan = new DependencyLinkSpan.Builder(s.parentId, s.id);
        for (BinaryAnnotation a : s.binaryAnnotations) {
          if (a.key.equals(Constants.CLIENT_ADDR) && a.endpoint != null) {
            linkSpan.caService(a.endpoint.serviceName);
          } else if (a.key.equals(Constants.SERVER_ADDR) && a.endpoint != null) {
            linkSpan.saService(a.endpoint.serviceName);
          }
        }
        for (Annotation a : s.annotations) {
          if (a.value.equals(Constants.SERVER_RECV) && a.endpoint != null) {
            linkSpan.srService(a.endpoint.serviceName);
            break;
          }
        }
        linkSpans.add(linkSpan.build());
      }

      linksBuilder.putTrace(linkSpans.iterator());
    }
    return linksBuilder.link();
  }

  @Override public void getDependencies(long endTs, @Nullable Long lookback,
      Callback<List<DependencyLink>> callback) {
    try {
      callback.onSuccess(getDependencies(endTs, lookback));
    } catch (RuntimeException | Error e) {
      callback.onError(e);
      if (e instanceof Error) throw e;
    }
  }

  private static boolean test(QueryRequest request, List<Span> spans) {
    Long timestamp = spans.get(0).timestamp;
    if (timestamp == null ||
        timestamp < (request.endTs - request.lookback) * 1000 ||
        timestamp > request.endTs * 1000) {
      return false;
    }
    Set<String> serviceNames = new LinkedHashSet<>();
    boolean testedDuration = request.minDuration == null && request.maxDuration == null;

    String spanName = request.spanName;
    Set<String> annotations = new LinkedHashSet<>(request.annotations);
    Map<String, String> binaryAnnotations = new LinkedHashMap<>(request.binaryAnnotations);

    Set<String> currentServiceNames = new LinkedHashSet<>();
    for (Span span : spans) {
      currentServiceNames.clear();

      for (Annotation a: span.annotations) {
        annotations.remove(a.value);
        if (a.endpoint != null) {
          serviceNames.add(a.endpoint.serviceName);
          currentServiceNames.add(a.endpoint.serviceName);
        }
      }

      for (BinaryAnnotation b: span.binaryAnnotations) {
        if (b.type == BinaryAnnotation.Type.STRING &&
            new String(b.value, UTF_8).equals(binaryAnnotations.get(b.key))) {
          binaryAnnotations.remove(b.key);
        }
        if (b.endpoint != null) {
          serviceNames.add(b.endpoint.serviceName);
          currentServiceNames.add(b.endpoint.serviceName);
        }
      }

      if (currentServiceNames.contains(request.serviceName) && !testedDuration) {
        if (request.minDuration != null && request.maxDuration != null) {
          testedDuration = span.duration >= request.minDuration && span.duration <= request.maxDuration;
        } else if (request.minDuration != null) {
          testedDuration = span.duration >= request.minDuration;
        }
      }

      if (span.name.equals(spanName)) {
        spanName = null;
      }
    }
    return serviceNames.contains(request.serviceName)
        && spanName == null
        && annotations.isEmpty()
        && binaryAnnotations.isEmpty()
        && testedDuration;
  }

  static final class LinkedListMultimap<K, V> extends Multimap<K, V> {

    @Override
    Collection<V> valueContainer() {
      return new LinkedList<>();
    }
  }

  /** QueryRequest.limit needs trace ids are returned in timestamp descending order. */
  static final class SortedByValue2Descending<K> extends Multimap<K, Pair<Long>> {

    @Override
    Set<Pair<Long>> valueContainer() {
      return new TreeSet<>(VALUE_2_DESCENDING);
    }

    final Comparator<Pair<Long>> VALUE_2_DESCENDING = new Comparator<Pair<Long>>() {
      @Override
      public int compare(Pair<Long> left, Pair<Long> right) {
        return right._2.compareTo(left._2);
      }
    };
  }

  static final class LinkedHashSetMultimap<K, V> extends Multimap<K, V> {

    @Override
    Collection<V> valueContainer() {
      return new LinkedHashSet<>();
    }
  }

  static abstract class Multimap<K, V> {
    private final Map<K, Collection<V>> delegate = new LinkedHashMap<>();

    abstract Collection<V> valueContainer();

    Set<K> keySet() {
      return delegate.keySet();
    }

    void put(K key, V value) {
      Collection<V> valueContainer = delegate.get(key);
      if (valueContainer == null) {
        synchronized (delegate) {
          if (!delegate.containsKey(key)) {
            valueContainer = valueContainer();
            delegate.put(key, valueContainer);
          }
        }
      }
      valueContainer.add(value);
    }

    // not synchronized as only used for for testing
    void clear() {
      delegate.clear();
    }

    Collection<V> get(K key) {
      return delegate.get(key);
    }
  }
}
