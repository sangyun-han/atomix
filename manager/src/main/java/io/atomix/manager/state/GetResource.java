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
 * limitations under the License.
 */
package io.atomix.manager.state;

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.Command;

/**
 * Get resource command.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class GetResource extends KeyOperation<Long> implements Command<Long> {
  private int type;

  public GetResource() {
  }

  /**
   * @throws NullPointerException if {@code path} or {@code type} are null
   */
  public GetResource(String key, int type) {
    super(key);
    this.type = Assert.argNot(type, type == 0, "type cannot be 0");
  }

  @Override
  public ConsistencyLevel consistency() {
    return ConsistencyLevel.LINEARIZABLE;
  }

  @Override
  public CompactionMode compaction() {
    return CompactionMode.QUORUM;
  }

  /**
   * Returns the resource type.
   *
   * @return The resource type.
   */
  public int type() {
    return type;
  }

  @Override
  public void writeObject(BufferOutput<?> buffer, Serializer serializer) {
    super.writeObject(buffer, serializer);
    buffer.writeShort((short) type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void readObject(BufferInput<?> buffer, Serializer serializer) {
    super.readObject(buffer, serializer);
    type = buffer.readShort();
  }

}
