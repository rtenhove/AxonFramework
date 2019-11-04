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

import io.axoniq.axonserver.grpc.MetaDataValue;
import io.axoniq.axonserver.grpc.command.Command;
import io.axoniq.axonserver.grpc.command.CommandProviderOutbound;
import io.axoniq.axonserver.grpc.command.CommandResponse;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.commandhandling.*;
import org.axonframework.messaging.MetaData;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Author: marc
 */
@RunWith(Parameterized.class)
public class CommandSerializerTest {

    private final CommandSerializer testSubject;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<?> data() {
        return Arrays.asList(new Object[]{"JacksonSerializer", JacksonSerializer.defaultSerializer()},
                             new Object[]{"XStreamSerializer", XStreamSerializer.defaultSerializer()});
    }

    public CommandSerializerTest(@SuppressWarnings("unused") String name, Serializer serializer) {
        AxonServerConfiguration configuration = new AxonServerConfiguration() {{
            this.setClientId("client");
            this.setComponentName("component");
        }};
        testSubject = new CommandSerializer(serializer, configuration);
    }

    @Test
    public void testSerializeRequest() {
        Map<String, ?> metadata = new HashMap<String, Object>() {{
            this.put("firstKey", "firstValue");
            this.put("secondKey", "secondValue");
        }};
        CommandMessage message = new GenericCommandMessage<>("payload", metadata);
        Command command = testSubject.serialize(message, "routingKey", 1);
        CommandMessage<?> deserialize = testSubject.deserialize(command);

        assertEquals(message.getIdentifier(), deserialize.getIdentifier());
        assertEquals(message.getCommandName(), deserialize.getCommandName());
        assertEquals(message.getMetaData(), deserialize.getMetaData());
        assertEquals(message.getPayloadType(), deserialize.getPayloadType());
        assertEquals(message.getPayload(), deserialize.getPayload());
    }

    @Test
    public void testSerializeResponse() {
        CommandResultMessage response = new GenericCommandResultMessage<>("response",
                                                                          MetaData.with("test", "testValue"));
        CommandProviderOutbound outbound = testSubject.serialize(response, "requestIdentifier");
        CommandResultMessage deserialize = testSubject.deserialize(outbound.getCommandResponse());

        assertEquals(response.getIdentifier(), deserialize.getIdentifier());
        assertEquals(response.getPayload(), deserialize.getPayload());
        assertEquals(response.getMetaData(), deserialize.getMetaData());
        assertFalse(response.isExceptional());
        assertFalse(response.optionalExceptionResult().isPresent());
    }

    @Test
    public void testSerializeExceptionalResponse() {
        RuntimeException exception = new RuntimeException("oops");
        CommandResultMessage response = new GenericCommandResultMessage<>(exception,
                                                                          MetaData.with("test", "testValue"));
        CommandProviderOutbound outbound = testSubject.serialize(response, "requestIdentifier");
        CommandResultMessage deserialize = testSubject.deserialize(outbound.getCommandResponse());

        assertEquals(response.getIdentifier(), deserialize.getIdentifier());
        assertEquals(response.getMetaData(), deserialize.getMetaData());
        assertTrue(deserialize.isExceptional());
        assertTrue(deserialize.optionalExceptionResult().isPresent());
        assertEquals(exception.getMessage(), deserialize.exceptionResult().getMessage());
    }

    @Test
    public void testSerializeExceptionalResponseWithDetails() {
        Exception exception = new CommandExecutionException("oops", null, "Details");
        CommandResultMessage<?> response = new GenericCommandResultMessage<>(exception,
                                                                             MetaData.with("test", "testValue"));
        CommandProviderOutbound outbound = testSubject.serialize(response, "requestIdentifier");
        assertEquals(response.getIdentifier(), outbound.getCommandResponse().getMessageIdentifier());
        CommandResultMessage<?> deserialize = testSubject.deserialize(outbound.getCommandResponse());

        assertEquals(response.getIdentifier(), deserialize.getIdentifier());
        assertEquals(response.getMetaData(), deserialize.getMetaData());
        assertTrue(deserialize.isExceptional());
        assertTrue(deserialize.optionalExceptionResult().isPresent());
        assertEquals(exception.getMessage(), deserialize.exceptionResult().getMessage());
        Throwable actual = deserialize.optionalExceptionResult().get();
        assertTrue(actual instanceof CommandExecutionException);
        assertEquals("Details", ((CommandExecutionException) actual).getDetails().orElse("None"));
    }

    @Test
    public void testDeserializeResponseWithoutPayload() {
        CommandResponse response = CommandResponse.newBuilder()
                                                  .setRequestIdentifier("requestId")
                                                  .putAllMetaData(Collections.singletonMap("meta-key", MetaDataValue.newBuilder().setTextValue("meta-value").build()))
                                                  .build();

        CommandResultMessage<Object> actual = testSubject.deserialize(response);
        assertEquals(Void.class, actual.getPayloadType());
        assertNull(actual.getPayload());
        assertEquals("meta-value", actual.getMetaData().get("meta-key"));
    }
}
