/*
 * Copyright 2016 the original author or authors.
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
package io.atomix;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.PropertiesReader;
import io.atomix.manager.ResourceClient;
import io.atomix.manager.ResourceServer;
import io.atomix.resource.util.ResourceTypeResolver;
import io.atomix.util.ClientProperties;

import java.util.Collection;
import java.util.Properties;

/**
 * Provides an interface for creating and operating on {@link io.atomix.resource.Resource}s remotely.
 * <p>
 * This {@link ResourceClient} implementation facilitates working with {@link io.atomix.resource.Resource}s remotely as
 * a client of the Atomix cluster. To create a client, construct a client builder via {@link #builder(Address...)}.
 * The builder requires a list of {@link Address}es to which to connect.
 * <pre>
 *   {@code
 *   List<Address> servers = Arrays.asList(
 *     new Address("123.456.789.0", 5000),
 *     new Address("123.456.789.1", 5000)
 *   );
 *   Atomix atomix = AtomixClient.builder(servers)
 *     .withTransport(new NettyTransport())
 *     .build();
 *   }
 * </pre>
 * The {@link Address} list does not have to include all servers in the cluster, but must include at least one live
 * server in order for the client to connect. Once the client connects to the cluster and opens a session, the client
 * will receive an updated list of servers to which to connect.
 * <p>
 * Clients communicate with the cluster via a {@link Transport}. By default, the {@code NettyTransport} is used if
 * no transport is explicitly configured. Thus, if no transport is configured then the Netty transport is expected
 * to be available on the classpath.
 * <h2>Client lifecycle</h2>
 * When a client is {@link #open() started}, the client will attempt to contact random servers in the provided
 * {@link Address} list to open a new session. Opening a client session requires only that the client be able to
 * communicate with at least one server which can communicate with the leader. Once a session has been opened,
 * the client will periodically send keep-alive requests to the cluster to maintain its session. In the event
 * that the client crashes or otherwise becomes disconnected from the cluster, the client's session will expire
 * after a configured session timeout and the client will have to open a new session to reconnect.
 * <p>
 * Clients may connect to and communicate with any server in the cluster. Typically, once a client connects to a
 * server, the client will attempt to remain connected to that server until some exceptional case occurs. Exceptional
 * cases may include a failure of the server, a network partition, or the server falling too far out of sync with
 * the rest of the cluster. When a failure in the cluster occurs and the client becomes disconnected from the cluster,
 * it will transparently reconnect to random servers until it finds a reachable server.
 * <p>
 * During certain cluster events such as leadership changes, the client may not be able to communicate with the
 * cluster for some arbitrary (but typically short) period of time. During that time, Atomix guarantees that the
 * client's session will not expire even if its timeout elapses. Once a new leader is elected, the client's session
 * timeout is reset.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class AtomixClient extends Atomix {

  /**
   * Returns a new Atomix replica builder from the given configuration file.
   *
   * @param properties The properties file from which to load the replica builder.
   * @return The replica builder.
   */
  public static Builder builder(String properties) {
    return builder(PropertiesReader.load(properties).properties());
  }

  /**
   * Returns a new Atomix replica builder from the given properties.
   *
   * @param properties The properties from which to load the replica builder.
   * @return The replica builder.
   */
  public static Builder builder(Properties properties) {
    ClientProperties clientProperties = new ClientProperties(properties);
    return builder(clientProperties.replicas())
      .withTransport(clientProperties.transport())
      .withSerializer(clientProperties.serializer());
  }

  /**
   * Returns a new Atomix client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Address... members) {
    return new Builder(ResourceClient.builder(members));
  }

  /**
   * Returns a new Atomix client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Collection<Address> members) {
    return new Builder(ResourceClient.builder(members));
  }

  /**
   * Builds the underlying resource client from the given properties.
   */
  private static ResourceClient buildClient(Properties properties) {
    ClientProperties clientProperties = new ClientProperties(properties);
    return ResourceClient.builder(clientProperties.replicas())
      .withTransport(clientProperties.transport())
      .build();
  }

  /**
   * Constructs a client from the given properties.
   *
   * @param properties The properties from which to construct the client.
   */
  public AtomixClient(Properties properties) {
    this(buildClient(properties));
  }

  /**
   * Constructs a client for the given resource client.
   *
   * @param client The resource client.
   */
  public AtomixClient(ResourceClient client) {
    super(client);
  }

  /**
   * Builder for programmatically constructing an {@link AtomixClient}.
   * <p>
   * The client builder configures an {@link AtomixClient} to connect to a cluster of {@link ResourceServer}s.
   * To create a client builder, use the {@link #builder(Address...)} method.
   * <pre>
   *   {@code
   *   Atomix client = AtomixClient.builder(servers)
   *     .withTransport(new NettyTransport())
   *     .build();
   *   }
   * </pre>
   */
  public static class Builder implements io.atomix.catalyst.util.Builder<AtomixClient> {
    private final ResourceClient.Builder builder;

    private Builder(ResourceClient.Builder builder) {
      this.builder = Assert.notNull(builder, "builder");
    }

    /**
     * Sets the Atomix transport.
     * <p>
     * The configured transport should be the same transport as all other nodes in the cluster.
     * If no transport is explicitly provided, the instance will default to the {@code NettyTransport}
     * if available on the classpath.
     *
     * @param transport The Atomix transport.
     * @return The Atomix builder.
     * @throws NullPointerException if {@code transport} is {@code null}
     */
    public Builder withTransport(Transport transport) {
      builder.withTransport(transport);
      return this;
    }

    /**
     * Sets the Atomix serializer.
     * <p>
     * The serializer will be used to serialize and deserialize operations that are sent over the wire.
     *
     * @param serializer The Atomix serializer.
     * @return The Atomix builder.
     */
    public Builder withSerializer(Serializer serializer) {
      builder.withSerializer(serializer);
      return this;
    }

    /**
     * Sets the Atomix resource type resolver.
     *
     * @param resolver The resource type resolver.
     * @return The Atomix builder.
     */
    public Builder withResourceResolver(ResourceTypeResolver resolver) {
      builder.withResourceResolver(resolver);
      return this;
    }

    @Override
    public AtomixClient build() {
      return new AtomixClient(builder.build());
    }
  }

}
