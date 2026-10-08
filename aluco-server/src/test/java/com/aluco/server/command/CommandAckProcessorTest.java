package com.aluco.server.command;

import com.aluco.server.push.LivePush;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Cmdack routing: the piece that drives SENT -> ACKED/FAILED (v2 spec 5.1.2). */
class CommandAckProcessorTest {

    private CommandService commandService;
    private LivePush livePush;
    private CommandAckProcessor processor;

    @BeforeEach
    void setUp() {
        commandService = mock(CommandService.class);
        livePush = mock(LivePush.class);
        processor = new CommandAckProcessor(commandService, livePush);
    }

    private static MqttMessage msg(String json) {
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ackedCmdackResolvesCommandAndBroadcasts() {
        processor.handle("aluco/site-01/TH-0001/cmdack", msg(
                "{\"v\":1,\"cmdId\":\"c-1\",\"deviceId\":\"TH-0001\",\"ts\":1752739200000,"
                        + "\"status\":\"ACKED\",\"message\":\"interval=3s applied\"}"));

        verify(commandService).ack("c-1");
        verify(livePush).pushCommandEvent("c-1", "TH-0001", "ACKED");
    }

    @Test
    void failedCmdackRecordsTheReason() {
        processor.handle("aluco/site-01/TH-0002/cmdack", msg(
                "{\"v\":1,\"cmdId\":\"c-2\",\"deviceId\":\"TH-0002\",\"ts\":1752739200000,"
                        + "\"status\":\"FAILED\",\"message\":\"flash write error\"}"));

        verify(commandService).fail("c-2", "flash write error");
        verify(commandService, never()).ack(any());
    }

    @Test
    void wrongTopicSuffixIsDropped() {
        processor.handle("aluco/site-01/TH-0001/telemetry", msg(
                "{\"v\":1,\"cmdId\":\"c-3\",\"deviceId\":\"TH-0001\",\"status\":\"ACKED\"}"));

        verifyNoInteractions(commandService, livePush);
    }

    @Test
    void invalidEnvelopeIsDroppedWithoutTouchingTheDatabase() {
        processor.handle("aluco/site-01/TH-0001/cmdack", msg("{\"v\":2,\"cmdId\":\"c-4\"}"));
        processor.handle("aluco/site-01/TH-0001/cmdack", msg("not json"));

        verifyNoInteractions(commandService, livePush);
    }
}
