package com.solarys.rfid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.time.Instant;

final class BridgeEmitter {
    private final ObjectMapper mapper;
    private final PrintWriter out;

    BridgeEmitter(ObjectMapper mapper, PrintWriter out) {
        this.mapper = mapper;
        this.out = out;
    }

    void emitSuccess(String commandId, String event, JsonNode data) {
        ObjectNode envelope = baseEnvelope(event, data);
        envelope.put("id", commandId);
        envelope.put("ok", true);
        write(envelope);
    }

    void emitError(String commandId, String error) {
        ObjectNode envelope = baseEnvelope("error", null);
        if (commandId != null) {
            envelope.put("id", commandId);
        }
        envelope.put("ok", false);
        envelope.put("error", error);
        write(envelope);
    }

    void emitPush(String event, JsonNode data) {
        ObjectNode envelope = baseEnvelope(event, data);
        envelope.put("ok", true);
        write(envelope);
    }

    private ObjectNode baseEnvelope(String event, JsonNode data) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("event", event);
        envelope.put("timestamp", Instant.now().toString());
        if (data != null) {
            envelope.set("data", data);
        }
        return envelope;
    }

    private synchronized void write(ObjectNode payload) {
        out.println(payload.toString());
        out.flush();
    }
}
