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

import com.twitter.zipkin.common.Span;
import com.twitter.zipkin.storage.DependencyStore;
import com.twitter.zipkin.storage.DependencyStoreSpec;
import scala.collection.immutable.List;
import zipkin.interop.ScalaDependencyStoreAdapter;
import zipkin.interop.AsyncToScalaSpanStoreAdapter;

public class InMemoryScalaDependencyStoreTest extends DependencyStoreSpec {
  private InMemorySpanStore mem = new InMemorySpanStore();

  @Override
  public DependencyStore store() {
    return new ScalaDependencyStoreAdapter(mem);
  }

  @Override
  public void processDependencies(List<Span> spans) {
    new AsyncToScalaSpanStoreAdapter(mem, mem).apply(spans);
  }

  public void clear() {
    mem.clear();
  }
}
