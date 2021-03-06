/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.resource.util;

import io.atomix.copycat.client.CopycatClient;
import io.atomix.resource.Resource;

/**
 * Constructs a resource instance given a client and resource options.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
@FunctionalInterface
public interface ResourceFactory<T extends Resource<T>> {

  /**
   * Creates a new resource.
   *
   * @param client The Copycat client.
   * @param options The resource options.
   * @return The created resource.
   */
  T create(CopycatClient client, Resource.Options options);

}
