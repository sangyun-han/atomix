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
package io.atomix.resource;

import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Listener;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.Command;
import io.atomix.copycat.Query;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.session.Session;
import io.atomix.resource.util.ResourceCommand;
import io.atomix.resource.util.ResourceQuery;

import java.io.Serializable;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Base class for fault-tolerant stateful distributed objects.
 * <p>
 * Resources are stateful distributed objects that run across a set of {@link io.atomix.copycat.server.CopycatServer}s
 * in a cluster.
 * <p>
 * Resources can be created either as standalone {@link io.atomix.copycat.server.StateMachine}s in
 * a typical Copycat cluster or as {@code Atomix} resources. Operations on the resource are translated into
 * {@link Command}s and {@link Query}s which are submitted to the cluster where they're logged and
 * replicated.
 * <p>
 * Resource implementations must be annotated with the {@link ResourceTypeInfo} annotation to indicate the
 * {@link ResourceStateMachine} to be used by the Atomix resource manager. Additionally, resources must be
 * registered via the {@code ServiceLoader} pattern in a file at {@code META-INF/services/io.atomix.resource.Resource}
 * on the class path. The resource registration allows the Atomix resource manager to locate and load the resource
 * state machine on each server in the cluster.
 * <p>
 * Resources have varying consistency guarantees depending on the configured resource {@link WriteConsistency}
 * and {@link ReadConsistency} and the semantics of the specific resource implementation.
 *
 * @param <T> resource type
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class Resource<T extends Resource<T>> implements Managed<T> {

  /**
   * Base class for cluster-wide resource configurations.
   * <p>
   * Configurations can be {@link #configure(Config) submitted} by any client with an open {@link Resource}
   * instance. Configurations are cluster-wide and affect the resource from the perspective of <em>all</em>
   * clients. Specifically, resource configurations are committed to the cluster, replicated, and applied
   * to the server-side replicated state machine for the resource.
   * <p>
   * Resource configurations control options specific to the resource's replicated {@link ResourceStateMachine}.
   * These options might include a maximum collection size or the order of values in a multi-map.
   */
  public static class Config extends Properties implements Serializable {
    public Config() {
    }

    public Config(Properties defaults) {
      super(defaults);
    }
  }

  /**
   * Base class for local resource options.
   * <p>
   * Options are configurations that are specific to a local resource instance. The first time a resource
   * is created by a client, the client may provide {@code Options} specifying the behavior of the resource
   * instance. Those initial options configure the behavior of the resource instance on the local node only.
   * For instance, options may include an {@link io.atomix.catalyst.transport.Address Address} to which to
   * bind a server or a default {@link WriteConsistency} level.
   */
  public static class Options extends Properties {
    public Options() {
    }

    public Options(Properties defaults) {
      super(defaults);
    }
  }

  /**
   * Resource session state constants.
   * <p>
   * The resource state is indicative of the resource's ability to communicate with the cluster within the
   * context of the underlying {@link Session}. In some cases, resource state changes may be indicative of a
   * loss of guarantees. Users of the resource should {@link Resource#onStateChange(Consumer) watch} the state
   * of a resource to determine when guarantees are lost and react to changes in the resource's ability to communicate
   * with the cluster.
   * <p>
   * <pre>
   *   {@code
   *   resource.onStateChange(state -> {
   *     switch (state) {
   *       case OPEN:
   *         // The resource is healthy
   *         break;
   *       case SUSPENDED:
   *         // The resource is unhealthy and operations may be unsafe
   *         break;
   *       case CLOSED:
   *         // The resource has been closed and pending operations have failed
   *         break;
   *     }
   *   });
   *   }
   * </pre>
   * So long as the resource is in the {@link #CONNECTED} state, all guarantees with respect to reads and writes will
   * be maintained, and a loss of the {@code CONNECTED} state may indicate a loss of linearizability. See the specific
   * states for more info.
   * <p>
   * Reference resource documentation for implications of the various states on specific resources.
   */
  public enum State {

    /**
     * Indicates that the resource is connected and operating normally.
     * <p>
     * The {@code CONNECTED} state indicates that the resource is healthy and operating normally. Operations submitted
     * and completed while the resource is in this state are guaranteed to adhere to the configured consistency level.
     */
    CONNECTED,

    /**
     * Indicates that the resource is suspended and its session may or may not be expired.
     * <p>
     * The {@code SUSPENDED} state is indicative of an inability to communicate with the cluster within the context of
     * the underlying client's {@link Session}. Operations performed on resources in this state should be considered
     * unsafe. An operation performed on a {@link #CONNECTED} resource that transitions to the {@code SUSPENDED} state
     * prior to the operation's completion may be committed multiple times in the event that the underlying session
     * is ultimately {@link Session.State#EXPIRED expired}, thus breaking linearizability. Additionally, state machines
     * may see the session expire while the resource is in this state.
     * <p>
     * A resource that is in the {@code SUSPENDED} state may transition back to {@link #CONNECTED} once its underlying
     * session is recovered. However, operations not yet completed prior to the resource's recovery may lose linearizability
     * guarantees. If an operation is submitted while a resource is in the {@link #CONNECTED} state and the resource loses
     * and recovers its session, the operation may be applied to the resource's state more than once. Operations completed
     * across sessions are guaranteed to be performed at-least-once only.
     */
    SUSPENDED,

    /**
     * Indicates that the resource is closed.
     * <p>
     * A resource may transition to this state as a result of an expired session or an explicit {@link Resource#close() close}
     * by the user or a closure of the resource's underlying client.
     */
    CLOSED

  }

  private final ResourceType type;
  protected final CopycatClient client;
  protected final Options options;
  private State state;
  private final Set<StateChangeListener> changeListeners = new CopyOnWriteArraySet<>();
  private WriteConsistency writeConsistency = WriteConsistency.ATOMIC;
  private ReadConsistency readConsistency = ReadConsistency.ATOMIC;

  protected Resource(CopycatClient client, Options options) {
    this.type = new ResourceType(getClass());
    this.client = Assert.notNull(client, "client");

    client.serializer().register(ResourceCommand.class, -50);
    client.serializer().register(ResourceQuery.class, -51);
    client.serializer().register(ResourceCommand.Configure.class, -52);
    client.serializer().register(ResourceCommand.Delete.class, -53);

    try {
      client.serializer().resolve(type.typeResolver().newInstance());
    } catch (InstantiationException | IllegalAccessException e) {
      throw new ResourceException("failed to instantiate resource type resolver");
    }

    this.options = options;
    client.onStateChange(this::onStateChange);
  }

  /**
   * Called when a client state change occurs.
   */
  private void onStateChange(CopycatClient.State state) {
    this.state = State.valueOf(state.name());
    changeListeners.forEach(l -> l.accept(this.state));
  }

  /**
   * Returns the resource type.
   *
   * @return The resource type.
   */
  public ResourceType type() {
    return type;
  }

  /**
   * Returns the current resource state.
   * <p>
   * The resource's {@link State} is indicative of the resource's ability to communicate with the cluster at any given
   * time. Users of the resource should use the state to determine when guarantees may be lost. See the {@link State}
   * documentation for information on the specific states, and see resource implementation documentation for the
   * implications of different states on resource consistency.
   *
   * @return The current resource state.
   */
  public State state() {
    return state;
  }

  /**
   * Registers a resource state change listener.
   *
   * @param callback The callback to call when the resource state changes.
   * @return The state change listener.
   */
  public Listener<State> onStateChange(Consumer<State> callback) {
    return new StateChangeListener(Assert.notNull(callback, "callback"));
  }

  /**
   * Returns the resource thread context.
   *
   * @return The resource thread context.
   */
  public ThreadContext context() {
    return client.context();
  }

  /**
   * Configures the cluster-wide resource.
   * <p>
   * The resource configuration will be submitted to the cluster, replicated, and applied on all
   * state machines in the cluster. Once successful, the configuration is guaranteed to be applied
   * on all replicas and all resource instances will rely on the updated configuration for linearizable
   * operations.
   *
   * @param config The resource configuration.
   * @return A completable future to be completed once the resource has been configured.
   */
  @SuppressWarnings("unchecked")
  public CompletableFuture<T> configure(Config config) {
    return client.submit(new ResourceCommand.Configure(config)).thenApply(v -> (T) this);
  }

  /**
   * Returns the configured write consistency level.
   * <p>
   * Resource consistency levels are configured via the {@link #with(WriteConsistency)} setter. By default,
   * all resources submit commands under the {@link WriteConsistency#ATOMIC} consistency level. See the
   * {@link WriteConsistency} documentation for information about guarantees provided by specific consistency
   * levels.
   *
   * @return The configured resource consistency level.
   */
  public WriteConsistency writeConsistency() {
    return writeConsistency;
  }

  /**
   * Sets the write consistency level.
   * <p>
   * The configured consistency level specifies how operations on the resource should be handled by the
   * cluster. Consistency levels dictate the order in which reads, writes, and events should be handled
   * by the Atomix cluster and the consistency requirements for completing different types of operations.
   * <p>
   * Note that consistency configurations apply only to a single instance of a distributed resource. Two
   * instances of the same resource on the same or different nodes can have different consistency requirements,
   * and the cluster will obey those differences.
   * <p>
   * By default, all resource operations are submitted to the cluster with the {@link WriteConsistency#ATOMIC}
   * consistency level. Atomic consistency means that the distributed resource will behave as a single
   * object for all instances. Users can decrease the default consistency level, but note that in some
   * cases resource implementations may override the configured {@link WriteConsistency} for safety. For instance,
   * a leader election may enforce atomic consistency at all times to ensure no two leaders can be
   * elected at the same time.
   *
   * @param consistency The write consistency level.
   * @return The resource instance.
   * @throws NullPointerException if {@code consistency} is null
   */
  @SuppressWarnings("unchecked")
  public T with(WriteConsistency consistency) {
    this.writeConsistency = Assert.notNull(consistency, "consistency");
    return (T) this;
  }

  /**
   * Returns the configured read consistency level.
   * <p>
   * Resource consistency levels are configured via the {@link #with(ReadConsistency)} setter. By default,
   * all resources submit commands under the {@link ReadConsistency#ATOMIC} consistency level. See the
   * {@link ReadConsistency} documentation for information about guarantees provided by specific consistency
   * levels.
   *
   * @return The configured resource consistency level.
   */
  public ReadConsistency readConsistency() {
    return readConsistency;
  }

  /**
   * Sets the read consistency level.
   * <p>
   * The configured consistency level specifies how operations on the resource should be handled by the
   * cluster. Consistency levels dictate the order in which reads, writes, and events should be handled
   * by the Atomix cluster and the consistency requirements for completing different types of operations.
   * <p>
   * Note that consistency configurations apply only to a single instance of a distributed resource. Two
   * instances of the same resource on the same or different nodes can have different consistency requirements,
   * and the cluster will obey those differences.
   * <p>
   * By default, all resource operations are submitted to the cluster with the {@link WriteConsistency#ATOMIC}
   * consistency level. Atomic consistency means that the distributed resource will behave as a single
   * object for all instances. Users can decrease the default consistency level, but note that in some
   * cases resource implementations may override the configured {@link WriteConsistency} for safety. For instance,
   * a leader election may enforce atomic consistency at all times to ensure no two leaders can be
   * elected at the same time.
   *
   * @param consistency The read consistency level.
   * @return The resource instance.
   * @throws NullPointerException if {@code consistency} is null
   */
  @SuppressWarnings("unchecked")
  public T with(ReadConsistency consistency) {
    this.readConsistency = Assert.notNull(consistency, "consistency");
    return (T) this;
  }

  /**
   * Submits a write operation for this resource to the cluster.
   * <p>
   * The write operation will be submitted with the configured {@link WriteConsistency#level()} if
   * it does not explicitly override {@link Command#consistency()} to provide a static consistency level.
   *
   * @param command The command to submit.
   * @param <R> The command result type.
   * @return A completable future to be completed with the command result.
   * @throws NullPointerException if {@code command} is null
   */
  protected <R> CompletableFuture<R> submit(Command<R> command) {
    return client.submit(new ResourceCommand<>(Assert.notNull(command, "command"), writeConsistency.level()));
  }

  /**
   * Submits a write operation for this resource to the cluster.
   * <p>
   * The write operation will be submitted with the {@link WriteConsistency#level()} if
   * it does not explicitly override {@link Command#consistency()} to provide a static consistency level.
   *
   * @param command The command to submit.
   * @param consistency The consistency with which to submit the command.
   * @param <R> The command result type.
   * @return A completable future to be completed with the command result.
   * @throws NullPointerException if {@code command} is null
   */
  protected <R> CompletableFuture<R> submit(Command<R> command, WriteConsistency consistency) {
    return client.submit(new ResourceCommand<>(Assert.notNull(command, "command"), consistency.level()));
  }

  /**
   * Submits a read operation for this resource to the cluster.
   * <p>
   * The read operation will be submitted with the configured {@link ReadConsistency#level()} if
   * it does not explicitly override {@link Query#consistency()} to provide a static consistency level.
   *
   * @param query The query to submit.
   * @param <R> The query result type.
   * @return A completable future to be completed with the query result.
   * @throws NullPointerException if {@code query} is null
   */
  protected <R> CompletableFuture<R> submit(Query<R> query) {
    return client.submit(new ResourceQuery<>(Assert.notNull(query, "query"), readConsistency.level()));
  }

  /**
   * Submits a read operation for this resource to the cluster.
   * <p>
   * The read operation will be submitted with the {@link ReadConsistency#level()} if
   * it does not explicitly override {@link Query#consistency()} to provide a static consistency level.
   *
   * @param query The query to submit.
   * @param consistency The read consistency level.
   * @param <R> The query result type.
   * @return A completable future to be completed with the query result.
   * @throws NullPointerException if {@code query} is null
   */
  protected <R> CompletableFuture<R> submit(Query<R> query, ReadConsistency consistency) {
    return client.submit(new ResourceQuery<>(Assert.notNull(query, "query"), consistency.level()));
  }

  /**
   * Opens the resource.
   * <p>
   * Once the resource is opened, the resource will be transitioned to the {@link State#CONNECTED} state
   * and the returned {@link CompletableFuture} will be completed.
   *
   * @return A completable future to be completed once the resource is opened.
   */
  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<T> open() {
    return client.open().thenApply(v -> (T) this);
  }

  @Override
  public boolean isOpen() {
    return state != State.CLOSED;
  }

  /**
   * Closes the resource.
   * <p>
   * Once the resource is closed, the resource will be transitioned to the {@link State#CLOSED} state and
   * the returned {@link CompletableFuture} will be completed. Thereafter, attempts to operate on the resource
   * will fail.
   *
   * @return A completable future to be completed once the resource is closed.
   */
  @Override
  public CompletableFuture<Void> close() {
    return client.close();
  }

  @Override
  public boolean isClosed() {
    return state == State.CLOSED;
  }

  /**
   * Deletes the resource state.
   *
   * @return A completable future to be completed once the resource has been deleted.
   */
  public CompletableFuture<Void> delete() {
    return client.submit(new ResourceCommand.Delete());
  }

  @Override
  public int hashCode() {
    return 37 * 23 + client.hashCode();
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof Resource && ((Resource) object).client.session().id() == client.session().id();
  }

  @Override
  public String toString() {
    return String.format("%s[id=%s]", getClass().getSimpleName(), client.session().id());
  }

  /**
   * Resource state change listener.
   */
  private class StateChangeListener implements Listener<State> {
    private final Consumer<State> callback;

    private StateChangeListener(Consumer<State> callback) {
      this.callback = callback;
      changeListeners.add(this);
    }

    @Override
    public void accept(State state) {
      callback.accept(state);
    }

    @Override
    public void close() {
      changeListeners.remove(this);
    }
  }

}
