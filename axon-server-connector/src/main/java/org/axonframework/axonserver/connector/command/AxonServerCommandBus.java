/*
 * Copyright (c) 2010-2019. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector.command;

import io.axoniq.axonserver.grpc.ErrorMessage;
import io.axoniq.axonserver.grpc.command.*;
import io.grpc.stub.StreamObserver;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.axonframework.axonserver.connector.*;
import org.axonframework.axonserver.connector.util.ExceptionSerializer;
import org.axonframework.axonserver.connector.util.ExecutorServiceBuilder;
import org.axonframework.axonserver.connector.util.FlowControllingStreamObserver;
import org.axonframework.axonserver.connector.util.ResubscribableStreamObserver;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandResultMessage;
import org.axonframework.commandhandling.callbacks.NoOpCallback;
import org.axonframework.commandhandling.distributed.RoutingStrategy;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.Registration;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.axonframework.axonserver.connector.util.ProcessingInstructionHelper.priority;
import static org.axonframework.commandhandling.GenericCommandResultMessage.asCommandResultMessage;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Axon {@link CommandBus} implementation that connects to Axon Server to submit and receive commands and command
 * responses. Delegates incoming commands to the provided {@code localSegment}.
 *
 * @author Marc Gathier
 * @since 4.0
 */
public class AxonServerCommandBus implements CommandBus {

    private static final Logger logger = LoggerFactory.getLogger(AxonServerCommandBus.class);

    private final AxonServerConnectionManager axonServerConnectionManager;
    private final AxonServerConfiguration configuration;
    private final CommandBus localSegment;
    private final CommandSerializer serializer;
    private final RoutingStrategy routingStrategy;
    private final CommandPriorityCalculator priorityCalculator;

    private final CommandProcessor commandProcessor;
    private final DispatchInterceptors<CommandMessage<?>> dispatchInterceptors;
    private final TargetContextResolver<? super CommandMessage<?>> targetContextResolver;
    private final CommandCallback<Object, Object> defaultCommandCallback;

    /**
     * Instantiate a Builder to be able to create an {@link AxonServerCommandBus}.
     * <p>
     * The {@link CommandPriorityCalculator} is defaulted to
     * {@link CommandPriorityCalculator#defaultCommandPriorityCalculator()} and the {@link TargetContextResolver}
     * defaults to a lambda returning the {@link AxonServerConfiguration#getContext()} as the context. The
     * {@link ExecutorServiceBuilder} defaults to {@link ExecutorServiceBuilder#defaultCommandExecutorServiceBuilder()}.
     * The {@link AxonServerConnectionManager}, the {@link AxonServerConfiguration}, the local {@link CommandBus},
     * {@link Serializer} and the {@link RoutingStrategy} are a <b>hard requirements</b> and as such should be provided.
     *
     * @return a Builder to be able to create a {@link AxonServerCommandBus}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Instantiate an Axon Server {@link CommandBus} client. Will connect to an Axon Server instance to submit and
     * receive commands and command responses. The {@link CommandPriorityCalculator} is defaulted to the
     * {@link CommandPriorityCalculator#defaultCommandPriorityCalculator()} and the {@link TargetContextResolver}
     * defaults to a lambda returning the output from {@link AxonServerConfiguration#getContext()}.
     *
     * @param axonServerConnectionManager a {@link AxonServerConnectionManager} which creates the connection to an Axon
     *                                    Server platform
     * @param configuration               the {@link AxonServerConfiguration} containing specifics like the client and
     *                                    component names used to identify the application in Axon Server among others
     * @param localSegment                a {@link CommandBus} handling the incoming commands for the local application
     * @param serializer                  a {@link Serializer} used for de/serialization command requests and responses
     * @param routingStrategy             a {@link RoutingStrategy} defining where a given {@link CommandMessage} should
     *                                    be routed to
     * @deprecated in favor of using the {@link Builder} (with the convenience {@link #builder()} method) to instantiate
     * an Axon Server Command Bus
     */
    @Deprecated
    public AxonServerCommandBus(AxonServerConnectionManager axonServerConnectionManager,
                                AxonServerConfiguration configuration,
                                CommandBus localSegment,
                                Serializer serializer,
                                RoutingStrategy routingStrategy) {
        this(axonServerConnectionManager, configuration, localSegment, serializer, routingStrategy,
             CommandPriorityCalculator.defaultCommandPriorityCalculator());
    }

    /**
     * Instantiate an Axon Server Command Bus client. Will connect to an Axon Server instance to submit and receive
     * commands. Allows specifying a {@link CommandPriorityCalculator} to define the priority of command message among
     * one another. The {@link TargetContextResolver} defaults to a lambda returning the
     * output from {@link AxonServerConfiguration#getContext()}.
     *
     * @param axonServerConnectionManager a {@link AxonServerConnectionManager} which creates the connection to an Axon
     *                                    Server platform
     * @param configuration               the {@link AxonServerConfiguration} containing client and component names used
     *                                    to identify the application in Axon Server
     * @param localSegment                a {@link CommandBus} handling the incoming commands for the local application
     * @param serializer                  a {@link Serializer} used for de/serialization command requests and responses
     * @param routingStrategy             a {@link RoutingStrategy} defining where a given {@link CommandMessage} should
     *                                    be routed to
     * @param priorityCalculator          a {@link CommandPriorityCalculator} calculating the request priority based on
     *                                    the content, and adds this priority to the request
     * @deprecated in favor of using the {@link Builder} (with the convenience {@link #builder()} method) to instantiate
     * an Axon Server Command Bus
     */
    @Deprecated
    public AxonServerCommandBus(AxonServerConnectionManager axonServerConnectionManager,
                                AxonServerConfiguration configuration,
                                CommandBus localSegment,
                                Serializer serializer,
                                RoutingStrategy routingStrategy,
                                CommandPriorityCalculator priorityCalculator) {
        this(axonServerConnectionManager, configuration, localSegment, serializer, routingStrategy, priorityCalculator,
             c -> configuration.getContext());
    }


    /**
     * Instantiate an Axon Server Command Bus client. Will connect to an Axon Server instance to submit and receive
     * commands.
     *
     * @param axonServerConnectionManager a {@link AxonServerConnectionManager} which creates the connection to an Axon
     *                                    Server platform
     * @param configuration               the {@link AxonServerConfiguration} containing client and component names used
     *                                    to identify the application in Axon Server
     * @param localSegment                a {@link CommandBus} handling the incoming commands for the local application
     * @param serializer                  a {@link Serializer} used for de/serialization command requests and responses
     * @param routingStrategy             a {@link RoutingStrategy} defining where a given {@link CommandMessage} should
     *                                    be routed to
     * @param priorityCalculator          a {@link CommandPriorityCalculator} calculating the request priority based on
     *                                    the content, and adds this priority to the request
     * @param targetContextResolver       resolves the context a given command should be dispatched in
     * @deprecated in favor of using the {@link Builder} (with the convenience {@link #builder()} method) to instantiate
     * an Axon Server Command Bus
     */
    @Deprecated
    public AxonServerCommandBus(AxonServerConnectionManager axonServerConnectionManager,
                                AxonServerConfiguration configuration,
                                CommandBus localSegment,
                                Serializer serializer,
                                RoutingStrategy routingStrategy,
                                CommandPriorityCalculator priorityCalculator,
                                TargetContextResolver<? super CommandMessage<?>> targetContextResolver) {
        this.axonServerConnectionManager = axonServerConnectionManager;
        this.configuration = configuration;
        this.localSegment = localSegment;
        this.serializer = new CommandSerializer(serializer, configuration);
        this.routingStrategy = routingStrategy;
        this.priorityCalculator = priorityCalculator;
        String context = configuration.getContext();
        this.targetContextResolver = targetContextResolver.orElse(m -> context);
        this.defaultCommandCallback = NoOpCallback.INSTANCE;

        this.commandProcessor = new CommandProcessor(
                context, configuration, ExecutorServiceBuilder.defaultCommandExecutorServiceBuilder()
        );

        dispatchInterceptors = new DispatchInterceptors<>();

        this.axonServerConnectionManager.addReconnectListener(context, this::resubscribe);
        this.axonServerConnectionManager.addDisconnectListener(context, this::unsubscribe);
    }

    /**
     * Instantiate a {@link AxonServerCommandBus} based on the fields contained in the {@link Builder}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link AxonServerCommandBus} instance
     */
    public AxonServerCommandBus(Builder builder) {
        builder.validate();
        this.axonServerConnectionManager = builder.axonServerConnectionManager;
        this.configuration = builder.configuration;
        this.localSegment = builder.localSegment;
        this.serializer = builder.buildSerializer();
        this.routingStrategy = builder.routingStrategy;
        this.priorityCalculator = builder.priorityCalculator;
        this.defaultCommandCallback = builder.defaultCommandCallback;

        String context = configuration.getContext();
        this.targetContextResolver = builder.targetContextResolver.orElse(m -> context);

        this.commandProcessor = new CommandProcessor(context, configuration, builder.executorServiceBuilder);

        dispatchInterceptors = new DispatchInterceptors<>();

        this.axonServerConnectionManager.addReconnectListener(context, this::resubscribe);
        this.axonServerConnectionManager.addDisconnectListener(context, this::unsubscribe);
    }

    private void resubscribe() {
        commandProcessor.resubscribe();
    }

    private void unsubscribe() {
        commandProcessor.unsubscribeAll();
    }

    @Override
    public <C> void dispatch(CommandMessage<C> command) {
        dispatch(command, defaultCommandCallback);
    }

    @Override
    public <C, R> void dispatch(CommandMessage<C> commandMessage,
                                CommandCallback<? super C, ? super R> commandCallback) {
        logger.debug("Dispatch command [{}] with callback", commandMessage.getCommandName());
        doDispatch(dispatchInterceptors.intercept(commandMessage), commandCallback);
    }

    private <C, R> void doDispatch(CommandMessage<C> commandMessage,
                                   CommandCallback<? super C, ? super R> commandCallback) {
        AtomicBoolean serverResponded = new AtomicBoolean(false);
        try {
            String context = targetContextResolver.resolveContext(commandMessage);
            Command command = serializer.serialize(commandMessage,
                                                   routingStrategy.getRoutingKey(commandMessage),
                                                   priorityCalculator.determinePriority(commandMessage));

            CommandServiceGrpc
                    .newStub(axonServerConnectionManager.getChannel(context))
                    .dispatch(command,
                              new StreamObserver<CommandResponse>() {
                                  @Override
                                  public void onNext(CommandResponse commandResponse) {
                                      serverResponded.set(true);
                                      logger.debug("Received command response [{}]", commandResponse);

                                      try {
                                          CommandResultMessage<R> resultMessage =
                                                  serializer.deserialize(commandResponse);
                                          commandCallback.onResult(commandMessage, resultMessage);
                                      } catch (Exception ex) {
                                          commandCallback.onResult(commandMessage, asCommandResultMessage(ex));
                                          logger.info("Failed to deserialize payload [{}] - Cause: {}",
                                                      commandResponse.getPayload().getData(),
                                                      ex.getCause().getMessage());
                                      }
                                  }

                                  @Override
                                  public void onError(Throwable throwable) {
                                      serverResponded.set(true);
                                      commandCallback.onResult(commandMessage, asCommandResultMessage(
                                              ErrorCode.COMMAND_DISPATCH_ERROR.convert(
                                                      configuration.getClientId(), throwable
                                              )
                                      ));
                                  }

                                  @Override
                                  public void onCompleted() {
                                      if (!serverResponded.get()) {
                                          ErrorMessage errorMessage =
                                                  ErrorMessage.newBuilder()
                                                              .setMessage("No result from command executor")
                                                              .build();
                                          commandCallback.onResult(commandMessage, asCommandResultMessage(
                                                  ErrorCode.COMMAND_DISPATCH_ERROR.convert(errorMessage))
                                          );
                                      }
                                  }
                              }
                    );
        } catch (Exception e) {
            logger.debug("There was a problem dispatching command [{}].", commandMessage, e);
            commandCallback.onResult(
                    commandMessage,
                    asCommandResultMessage(ErrorCode.COMMAND_DISPATCH_ERROR.convert(configuration.getClientId(), e))
            );
        }
    }

    @Override
    public Registration subscribe(String commandName, MessageHandler<? super CommandMessage<?>> messageHandler) {
        logger.debug("Subscribing command with name [{}]", commandName);
        Registration registration = localSegment.subscribe(commandName, messageHandler);
        commandProcessor.subscribe(commandName);
        return new AxonServerRegistration(
                registration,
                () -> commandProcessor.unsubscribe(commandName)
        );
    }

    @Override
    public Registration registerHandlerInterceptor(
            MessageHandlerInterceptor<? super CommandMessage<?>> handlerInterceptor) {
        return localSegment.registerHandlerInterceptor(handlerInterceptor);
    }

    /**
     * Disconnect the command bus from the Axon Server.
     */
    public void disconnect() {
        if (commandProcessor != null) {
            commandProcessor.disconnect();
        }
    }

    @Override
    public Registration registerDispatchInterceptor(
            MessageDispatchInterceptor<? super CommandMessage<?>> dispatchInterceptor) {
        return dispatchInterceptors.registerDispatchInterceptor(dispatchInterceptor);
    }

    /**
     * Builder class to instantiate an {@link AxonServerCommandBus}.
     * <p>
     * The {@link CommandPriorityCalculator} is defaulted to
     * {@link CommandPriorityCalculator#defaultCommandPriorityCalculator()} and the {@link TargetContextResolver}
     * defaults to a lambda returning the {@link AxonServerConfiguration#getContext()} as the context. The
     * {@link ExecutorServiceBuilder} defaults to {@link ExecutorServiceBuilder#defaultCommandExecutorServiceBuilder()}.
     * The {@link AxonServerConnectionManager}, the {@link AxonServerConfiguration}, the local {@link CommandBus},
     * {@link Serializer} and the {@link RoutingStrategy} are <b>hard requirements</b> and as such should be provided.
     */
    public static class Builder {

        private CommandCallback<Object, Object> defaultCommandCallback = NoOpCallback.INSTANCE;
        private AxonServerConnectionManager axonServerConnectionManager;
        private AxonServerConfiguration configuration;
        private CommandBus localSegment;
        private Serializer serializer;
        private RoutingStrategy routingStrategy;
        private CommandPriorityCalculator priorityCalculator =
                CommandPriorityCalculator.defaultCommandPriorityCalculator();
        private TargetContextResolver<? super CommandMessage<?>> targetContextResolver =
                c -> configuration.getContext();
        private ExecutorServiceBuilder executorServiceBuilder =
                ExecutorServiceBuilder.defaultCommandExecutorServiceBuilder();

        /**
         * Sets the {@link AxonServerConnectionManager} used to create connections between this application and an Axon
         * Server instance.
         *
         * @param axonServerConnectionManager an {@link AxonServerConnectionManager} used to create connections between
         *                                    this application and an Axon Server instance
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder axonServerConnectionManager(AxonServerConnectionManager axonServerConnectionManager) {
            assertNonNull(axonServerConnectionManager, "AxonServerConnectionManager may not be null");
            this.axonServerConnectionManager = axonServerConnectionManager;
            return this;
        }

        /**
         * Sets the {@link AxonServerConfiguration} used to configure several components within the Axon Server Command
         * Bus, like setting the client id or the number of command handling threads used.
         *
         * @param configuration an {@link AxonServerConfiguration} used to configure several components within the Axon
         *                      Server Command Bus
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder configuration(AxonServerConfiguration configuration) {
            assertNonNull(configuration, "AxonServerConfiguration may not be null");
            this.configuration = configuration;
            return this;
        }

        /**
         * Sets the local {@link CommandBus} used to dispatch incoming commands to the local environment.
         *
         * @param localSegment a {@link CommandBus} used to dispatch incoming commands to the local environment
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder localSegment(CommandBus localSegment) {
            assertNonNull(localSegment, "Local CommandBus may not be null");
            this.localSegment = localSegment;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize incoming and outgoing commands and command results.
         *
         * @param serializer a {@link Serializer} used to de-/serialize incoming and outgoing commands and command
         *                   results
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = serializer;
            return this;
        }

        /**
         * Sets the {@link RoutingStrategy} used to correctly configure connections between Axon clients and
         * Axon Server.
         *
         * @param routingStrategy a {@link RoutingStrategy}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder routingStrategy(RoutingStrategy routingStrategy) {
            assertNonNull(routingStrategy, "RoutingStrategy may not be null");
            this.routingStrategy = routingStrategy;
            return this;
        }

        /**
         * Sets the callback to use when commands are dispatched in a "fire and forget" method, such as
         * {@link #dispatch(CommandMessage)}. Defaults to a {@link NoOpCallback}. Passing {@code null} will result
         * in a {@link NoOpCallback} being used.
         *
         * @param defaultCommandCallback the callback to invoke when no explicit callback is provided for a command
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder defaultCommandCallback(CommandCallback<Object, Object> defaultCommandCallback) {
            this.defaultCommandCallback = getOrDefault(defaultCommandCallback, NoOpCallback.INSTANCE);
            return this;
        }

        /**
         * Sets the {@link CommandPriorityCalculator} used to deduce the priority of an incoming command among other
         * commands, to give precedence over high(er) valued queries for example. Defaults to a
         * {@link CommandPriorityCalculator#defaultCommandPriorityCalculator()}.
         *
         * @param priorityCalculator a {@link CommandPriorityCalculator} used to deduce the priority of an incoming
         *                           command among other commands
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder priorityCalculator(CommandPriorityCalculator priorityCalculator) {
            assertNonNull(priorityCalculator, "CommandPriorityCalculator may not be null");
            this.priorityCalculator = priorityCalculator;
            return this;
        }

        /**
         * Sets the {@link TargetContextResolver} used to resolve the target (bounded) context of an ingested
         * {@link CommandMessage}. Defaults to returning the {@link AxonServerConfiguration#getContext()} on any type of
         * command message being ingested.
         *
         * @param targetContextResolver a {@link TargetContextResolver} used to resolve the target (bounded) context of
         *                              an ingested {@link CommandMessage}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder targetContextResolver(TargetContextResolver<? super CommandMessage<?>> targetContextResolver) {
            assertNonNull(targetContextResolver, "TargetContextResolver may not be null");
            this.targetContextResolver = targetContextResolver;
            return this;
        }

        /**
         * Sets the {@link ExecutorServiceBuilder} which builds an {@link ExecutorService} based on a given
         * {@link AxonServerConfiguration} and {@link BlockingQueue} of {@link Runnable}. This ExecutorService is used
         * to process incoming commands with. Defaults to a {@link ThreadPoolExecutor}, using the
         * {@link AxonServerConfiguration#getCommandThreads()} for the pool size, a keep-alive-time of {@code 100ms},
         * the given BlockingQueue as the work queue and an {@link AxonThreadFactory}.
         * <p/>
         * Note that it is highly recommended to use the given BlockingQueue if you are to provide you own
         * {@code executorServiceBuilder}, as it ensure the command's priority is taken into consideration.
         * Defaults to {@link ExecutorServiceBuilder#defaultCommandExecutorServiceBuilder()}.
         *
         * @param executorServiceBuilder an {@link ExecutorServiceBuilder} used to build an {@link ExecutorService}
         *                               based on the {@link AxonServerConfiguration} and a {@link BlockingQueue}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder executorServiceBuilder(ExecutorServiceBuilder executorServiceBuilder) {
            assertNonNull(executorServiceBuilder, "ExecutorServiceBuilder may not be null");
            this.executorServiceBuilder = executorServiceBuilder;
            return this;
        }

        /**
         * Initializes a {@link AxonServerCommandBus} as specified through this Builder.
         *
         * @return a {@link AxonServerCommandBus} as specified through this Builder
         */
        public AxonServerCommandBus build() {
            return new AxonServerCommandBus(this);
        }

        /**
         * Build a {@link CommandSerializer} using the configured {@code serializer} and {@code configuration}.
         *
         * @return a {@link CommandSerializer} based on the configured {@code serializer} and {@code configuration}
         */
        protected CommandSerializer buildSerializer() {
            return new CommandSerializer(serializer, configuration);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(axonServerConnectionManager,
                          "The AxonServerConnectionManager is a hard requirement and should be provided");
            assertNonNull(configuration, "The AxonServerConfiguration is a hard requirement and should be provided");
            assertNonNull(localSegment, "The Local CommandBus is a hard requirement and should be provided");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided");
            assertNonNull(routingStrategy, "The RoutingStrategy is a hard requirement and should be provided");
        }
    }

    private class CommandProcessor {

        private static final int COMMAND_QUEUE_CAPACITY = 1000;
        private static final int DEFAULT_PRIORITY = 0;

        private final String context;
        private final CopyOnWriteArraySet<String> subscribedCommands;
        private final ExecutorService commandExecutor;

        private volatile boolean subscribing;
        private volatile boolean running = true;
        private volatile StreamObserver<CommandProviderOutbound> subscriberStreamObserver;

        CommandProcessor(String context,
                         AxonServerConfiguration configuration,
                         ExecutorServiceBuilder executorServiceBuilder) {
            this.context = context;
            subscribedCommands = new CopyOnWriteArraySet<>();
            PriorityBlockingQueue<Runnable> commandProcessQueue = new PriorityBlockingQueue<>(
                    COMMAND_QUEUE_CAPACITY,
                    Comparator.comparingLong(
                            r -> r instanceof CommandProcessingTask
                                    ? ((CommandProcessingTask) r).getPriority()
                                    : DEFAULT_PRIORITY
                    )
            );
            commandExecutor = executorServiceBuilder.apply(configuration, commandProcessQueue);
        }

        private void resubscribe() {
            if (subscribedCommands.isEmpty() || subscribing) {
                return;
            }

            logger.info("Resubscribing Command handlers with AxonServer");
            try {
                StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver(context);
                subscribedCommands.forEach(command -> outboundStreamObserver.onNext(
                        CommandProviderOutbound.newBuilder().setSubscribe(
                                CommandSubscription.newBuilder()
                                                   .setCommand(command)
                                                   .setComponentName(configuration.getComponentName())
                                                   .setClientId(configuration.getClientId())
                                                   .setMessageId(UUID.randomUUID().toString())
                                                   .build()
                        ).build()
                ));
            } catch (Exception e) {
                logger.warn("Error while resubscribing - [{}]", e.getMessage());
            }
        }

        public void subscribe(String commandName) {
            subscribing = true;
            subscribedCommands.add(commandName);
            try {
                StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver(context);
                outboundStreamObserver.onNext(CommandProviderOutbound.newBuilder().setSubscribe(
                        CommandSubscription.newBuilder()
                                           .setCommand(commandName)
                                           .setClientId(configuration.getClientId())
                                           .setComponentName(configuration.getComponentName())
                                           .setMessageId(UUID.randomUUID().toString())
                                           .build()
                ).build());
            } catch (Exception e) {
                logger.debug("Subscribing command with name [{}] to Axon Server failed. "
                                     + "Will resubscribe when connection is established.",
                             commandName, e);
            } finally {
                subscribing = false;
            }
        }

        private void processCommand(Command command) {
            StreamObserver<CommandProviderOutbound> outboundStreamObserver = getSubscriberObserver(context);
            try {
                dispatchLocal(serializer.deserialize(command), outboundStreamObserver);
            } catch (RuntimeException throwable) {
                logger.error("Error while dispatching command [{}] - Cause: {}",
                             command.getName(), throwable.getMessage(), throwable);

                if (outboundStreamObserver == null) {
                    return;
                }

                CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                        CommandResponse.newBuilder()
                                       .setMessageIdentifier(UUID.randomUUID().toString())
                                       .setRequestIdentifier(command.getMessageIdentifier())
                                       .setErrorCode(ErrorCode.COMMAND_DISPATCH_ERROR.errorCode())
                                       .setErrorMessage(
                                               ExceptionSerializer.serialize(configuration.getClientId(), throwable)
                                       )
                ).build();

                outboundStreamObserver.onNext(response);
            }
        }

        private synchronized StreamObserver<CommandProviderOutbound> getSubscriberObserver(String context) {
            if (subscriberStreamObserver != null) {
                return subscriberStreamObserver;
            }

            StreamObserver<CommandProviderInbound> commandsFromRoutingServer = new StreamObserver<CommandProviderInbound>() {
                @Override
                public void onNext(CommandProviderInbound commandToSubscriber) {
                    logger.debug("Received command from server: {}", commandToSubscriber);
                    if (commandToSubscriber.getRequestCase() == CommandProviderInbound.RequestCase.COMMAND) {
                        commandExecutor.execute(new CommandProcessingTask(commandToSubscriber.getCommand()));
                    }
                }

                @SuppressWarnings("Duplicates")
                @Override
                public void onError(Throwable ex) {
                    logger.warn("Command Inbound Stream closed with error", ex);
                    subscriberStreamObserver = null;
                }

                @Override
                public void onCompleted() {
                    logger.info("Received completed from server.");
                    subscriberStreamObserver = null;
                }
            };

            ResubscribableStreamObserver<CommandProviderInbound> resubscribableStreamObserver =
                    new ResubscribableStreamObserver<>(commandsFromRoutingServer, t -> resubscribe());

            StreamObserver<CommandProviderOutbound> streamObserver =
                    axonServerConnectionManager.getCommandStream(context, resubscribableStreamObserver);

            logger.info("Creating new command stream subscriber");

            subscriberStreamObserver = new FlowControllingStreamObserver<>(
                    streamObserver,
                    configuration,
                    flowControl -> CommandProviderOutbound.newBuilder().setFlowControl(flowControl).build(),
                    t -> t.getRequestCase().equals(CommandProviderOutbound.RequestCase.COMMAND_RESPONSE)
            ).sendInitialPermits();
            return subscriberStreamObserver;
        }

        public void unsubscribe(String command) {
            subscribedCommands.remove(command);
            try {
                getSubscriberObserver(context).onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                        CommandSubscription.newBuilder()
                                           .setCommand(command)
                                           .setClientId(configuration.getClientId())
                                           .setMessageId(UUID.randomUUID().toString())
                                           .build()
                ).build());
            } catch (Exception ignored) {
                // This exception is ignored
            }
        }

        private void unsubscribeAll() {
            for (String subscribedCommand : subscribedCommands) {
                try {
                    getSubscriberObserver(context).onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                            CommandSubscription.newBuilder()
                                               .setCommand(subscribedCommand)
                                               .setClientId(configuration.getClientId())
                                               .setMessageId(UUID.randomUUID().toString())
                                               .build()
                    ).build());
                } catch (Exception ignored) {
                    // This exception is ignored
                }
            }
            subscriberStreamObserver = null;
        }

        private <C> void dispatchLocal(CommandMessage<C> command,
                                       StreamObserver<CommandProviderOutbound> responseObserver) {
            logger.debug("Dispatch command [{}] locally", command.getCommandName());

            localSegment.dispatch(command, (commandMessage, commandResultMessage) -> {
                logger.debug("Dispatched command [{}] locally", command.getCommandName());
                responseObserver.onNext(serializer.serialize(commandResultMessage, command.getIdentifier()));
            });
        }

        void disconnect() {
            if (subscriberStreamObserver != null) {
                subscriberStreamObserver.onCompleted();
            }
            running = false;
            commandExecutor.shutdown();
        }

        /**
         * A {@link Runnable} implementation which is given to a {@link PriorityBlockingQueue} to be consumed by the
         * command {@link ExecutorService}, in order. The {@code priority} is retrieved from the provided
         * {@link Command} and used to priorities this {@link CommandProcessingTask} among others of it's kind.
         */
        private class CommandProcessingTask implements Runnable {

            private final long priority;
            private final Command command;

            private CommandProcessingTask(Command command) {
                this.priority = -priority(command.getProcessingInstructionsList());
                this.command = command;
            }

            public long getPriority() {
                return priority;
            }

            @Override
            public void run() {
                if (!running) {
                    logger.debug("Command Processor has stopped running, "
                                         + "hence command [{}] will no longer be processed", command.getName());
                    return;
                }

                try {
                    logger.debug("Will process command: {}", command);
                    processCommand(command);
                } catch (RuntimeException | OutOfDirectMemoryError e) {
                    logger.warn("Command Processor had an exception when processing command [{}]", command, e);
                }
            }
        }
    }
}
