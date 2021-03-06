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
package zipkin.junit;

import org.junit.Rule;
import zipkin.SpanStore;
import zipkin.SpanStoreTest;
import zipkin.async.AsyncSpanConsumer;

/** Tests the http interface of {@link ZipkinRule}. */
public class ZipkinRuleSpanStoreTest extends SpanStoreTest {

  @Rule
  public ZipkinRule server = new ZipkinRule();
  HttpSpanStore store = new HttpSpanStore(server.httpUrl());

  @Override protected SpanStore store() {
    return store;
  }

  @Override protected AsyncSpanConsumer consumer() {
    return store;
  }

  @Override
  public void clear() {
    // no need.. the test rule does this
  }
}
