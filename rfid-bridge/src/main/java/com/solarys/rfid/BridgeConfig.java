package com.solarys.rfid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mot.rfid.api3.READER_TYPE;
import com.mot.rfid.api3.SECURE_MODE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class BridgeConfig {
    final String host;
    final int port;
    final String username;
    final String password;
    final boolean forceLogin;
    final boolean autoStartInventory;
    final int reconnectDelayMs;
    final int connectTimeoutMs;
    final READER_TYPE readerType;
    final SECURE_MODE secureMode;
    final short[] antennas;
    final int tagBatchSize;

    BridgeConfig(String host,
                 int port,
                 String username,
                 String password,
                 boolean forceLogin,
                 boolean autoStartInventory,
                 int reconnectDelayMs,
                 int connectTimeoutMs,
                 READER_TYPE readerType,
                 SECURE_MODE secureMode,
                 short[] antennas,
                 int tagBatchSize) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.forceLogin = forceLogin;
        this.autoStartInventory = autoStartInventory;
        this.reconnectDelayMs = Math.max(1000, reconnectDelayMs);
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.readerType = readerType;
        this.secureMode = secureMode;
        this.antennas = antennas != null ? Arrays.copyOf(antennas, antennas.length) : null;
        this.tagBatchSize = Math.max(1, tagBatchSize);
    }

    boolean isValid() {
        return host != null && !host.isBlank();
    }

    boolean shouldLogin() {
        return username != null && !username.isBlank();
    }

    static BridgeConfig load(ObjectMapper mapper, Map<String, String> env, short[] defaultAntennas) {
        JsonNode fileNode = readConfigNode(mapper, env);
        String host = pickString(env, fileNode, "ZEBRA_READER_HOST", "host", null);
        int port = pickInt(env, fileNode, "ZEBRA_READER_PORT", "port", 5084);
        String username = pickString(env, fileNode, "ZEBRA_READER_USERNAME", "username", null);
        String password = pickString(env, fileNode, "ZEBRA_READER_PASSWORD", "password", null);
        boolean forceLogin = pickBoolean(env, fileNode, "ZEBRA_READER_FORCE_LOGIN", "forceLogin", true);
        boolean auto = pickBoolean(env, fileNode, "ZEBRA_READER_AUTO_INVENTORY", "autoStartInventory", true);
        int reconnect = pickInt(env, fileNode, "ZEBRA_READER_RECONNECT_MS", "reconnectDelayMs", 5000);
        int timeout = pickInt(env, fileNode, "ZEBRA_READER_TIMEOUT_MS", "connectTimeoutMs", 15000);
        String readerTypeRaw = pickString(env, fileNode, "ZEBRA_READER_TYPE", "readerType", "FX");
        String secureModeRaw = pickString(env, fileNode, "ZEBRA_READER_SECURE_MODE", "secureMode", "HTTP");
        short[] antennas = pickAntennaList(env, fileNode, "ZEBRA_READER_ANTENNAS", "antennas", defaultAntennas);
        int tagBatchSize = pickInt(env, fileNode, "ZEBRA_READER_TAG_BATCH", "tagBatchSize", 64);

        return new BridgeConfig(
            host,
            port,
            username,
            password,
            forceLogin,
            auto,
            reconnect,
            timeout,
            parseReaderType(readerTypeRaw),
            parseSecureMode(secureModeRaw),
            antennas,
            tagBatchSize
        );
    }

    private static JsonNode readConfigNode(ObjectMapper mapper, Map<String, String> env) {
        String explicit = env.getOrDefault("RFID_BRIDGE_CONFIG", "").trim();
        List<Path> candidates = new ArrayList<>();
        if (!explicit.isEmpty()) {
            candidates.add(Paths.get(explicit));
        } else {
            Path rootConfig = Paths.get("bridge-config.json");
            if (Files.exists(rootConfig)) {
                candidates.add(rootConfig);
            }
            Path nestedConfig = Paths.get("config", "bridge-config.json");
            if (Files.exists(nestedConfig)) {
                candidates.add(nestedConfig);
            }
        }
        for (Path path : candidates) {
            if (!Files.exists(path)) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(path)) {
                return mapper.readTree(stream);
            } catch (IOException e) {
                System.err.println("[rfid-bridge] Failed to read config file " + path + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static String pickString(Map<String, String> env, JsonNode node, String envKey, String jsonField, String defaultValue) {
        String envValue = env.get(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        if (node != null && node.hasNonNull(jsonField)) {
            String value = node.path(jsonField).asText();
            if (!value.isBlank()) {
                return value.trim();
            }
        }
        return defaultValue;
    }

    private static int pickInt(Map<String, String> env, JsonNode node, String envKey, String jsonField, int defaultValue) {
        String envValue = env.get(envKey);
        if (envValue != null && !envValue.isBlank()) {
            try {
                return Integer.parseInt(envValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (node != null && node.has(jsonField)) {
            JsonNode field = node.get(jsonField);
            if (field.isInt()) {
                return field.intValue();
            }
            if (field.isTextual()) {
                try {
                    return Integer.parseInt(field.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultValue;
    }

    private static boolean pickBoolean(Map<String, String> env, JsonNode node, String envKey, String jsonField, boolean defaultValue) {
        String envValue = env.get(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return Boolean.parseBoolean(envValue.trim());
        }
        if (node != null && node.has(jsonField)) {
            JsonNode field = node.get(jsonField);
            if (field.isBoolean()) {
                return field.booleanValue();
            }
            if (field.isTextual()) {
                return Boolean.parseBoolean(field.asText().trim());
            }
        }
        return defaultValue;
    }

    private static short[] pickAntennaList(Map<String, String> env, JsonNode node, String envKey, String jsonField, short[] defaultValue) {
        String raw = env.get(envKey);
        if (raw == null || raw.isBlank()) {
            if (node != null && node.has(jsonField)) {
                JsonNode field = node.get(jsonField);
                if (field.isArray()) {
                    List<Short> values = new ArrayList<>();
                    field.forEach(item -> {
                        if (item.canConvertToInt()) {
                            short value = (short) item.intValue();
                            if (value > 0) {
                                values.add(value);
                            }
                        }
                    });
                    if (!values.isEmpty()) {
                        short[] ants = new short[values.size()];
                        for (int i = 0; i < values.size(); i++) {
                            ants[i] = values.get(i);
                        }
                        return ants;
                    }
                } else if (field.isTextual()) {
                    raw = field.asText();
                }
            }
        }
        if (raw != null && !raw.isBlank()) {
            String[] tokens = raw.split(",");
            List<Short> values = new ArrayList<>();
            for (String token : tokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                try {
                    short value = Short.parseShort(token.trim());
                    if (value > 0) {
                        values.add(value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (!values.isEmpty()) {
                short[] ants = new short[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    ants[i] = values.get(i);
                }
                return ants;
            }
        }
        return defaultValue != null ? Arrays.copyOf(defaultValue, defaultValue.length) : null;
    }

    private static READER_TYPE parseReaderType(String raw) {
        if (raw == null || raw.isBlank()) {
            return READER_TYPE.FX;
        }
        String normalized = raw.trim().toUpperCase();
        if ("XR".equals(normalized)) {
            return READER_TYPE.XR;
        }
        if ("MC".equals(normalized)) {
            return READER_TYPE.MC;
        }
        return READER_TYPE.FX;
    }

    private static SECURE_MODE parseSecureMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SECURE_MODE.HTTP;
        }
        String normalized = raw.trim().toUpperCase();
        if ("HTTPS".equals(normalized)) {
            return SECURE_MODE.HTTPS;
        }
        return SECURE_MODE.HTTP;
    }
}
