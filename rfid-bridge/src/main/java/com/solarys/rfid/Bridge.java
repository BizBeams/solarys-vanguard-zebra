package com.solarys.rfid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight stdin/stdout bridge that will wrap the Zebra RFID Host SDK.
 * For now it only implements mock commands so we can wire up IPC safely
 * before the hardware specific logic lands.
 */
public class Bridge {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintWriter out;

    public Bridge(PrintWriter out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(System.out, true)) {
            new Bridge(out).eventLoop(in);
        }
    }

    private void eventLoop(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                handleCommand(line.trim());
            } catch (Exception e) {
                emitError(null, "Unhandled error: " + e.getMessage());
            }
        }
    }

    private void handleCommand(String rawJson) throws JsonProcessingException {
        JsonNode root = mapper.readTree(rawJson);
        String commandId = root.path("id").asText(UUID.randomUUID().toString());
        String cmd = root.path("cmd").asText();

        switch (cmd) {
            case "ping" -> emitSuccess(commandId, "pong", null);
            case "mockTag" -> emitTag(commandId, root.path("leadId").asText(null));
            case "shutdown" -> {
                emitSuccess(commandId, "shutdown", null);
                System.exit(0);
            }
            default -> emitError(commandId, "Unknown cmd: " + cmd);
        }
    }

    private void emitTag(String commandId, String leadId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("antenna", 1);
        payload.put("timestamp", Instant.now().toString());
        payload.put("tagId", "SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        if (leadId != null && !leadId.isBlank()) {
            payload.put("leadId", leadId);
        }
        emitSuccess(commandId, "tag", payload);
    }

    private void emitSuccess(String commandId, String event, JsonNode data) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", commandId);
        envelope.put("ok", true);
        envelope.put("event", event);
        envelope.put("timestamp", Instant.now().toString());
        if (data != null) {
            envelope.set("data", data);
        }
        write(envelope);
    }

    private void emitError(String commandId, String error) {
        ObjectNode envelope = mapper.createObjectNode();
        if (commandId != null) {
            envelope.put("id", commandId);
        }
        envelope.put("ok", false);
        envelope.put("error", error);
        envelope.put("timestamp", Instant.now().toString());
        write(envelope);
    }

    private void write(ObjectNode payload) {
        try {
            out.println(mapper.writeValueAsString(payload));
            out.flush();
        } catch (JsonProcessingException e) {
            System.err.println("Failed to serialize payload: " + e.getMessage());
        }
    }
}
