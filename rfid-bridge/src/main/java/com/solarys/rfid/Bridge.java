package com.solarys.rfid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mot.rfid.api3.ANTENNA_EVENT_TYPE;
import com.mot.rfid.api3.AntennaInfo;
import com.mot.rfid.api3.DISCONNECTION_EVENT_TYPE;
import com.mot.rfid.api3.InvalidUsageException;
import com.mot.rfid.api3.LoginInfo;
import com.mot.rfid.api3.OperationFailureException;
import com.mot.rfid.api3.RFIDReader;
import com.mot.rfid.api3.ReaderManagement;
import com.mot.rfid.api3.RfidEventsListener;
import com.mot.rfid.api3.RfidReadEvents;
import com.mot.rfid.api3.RfidStatusEvents;
import com.mot.rfid.api3.START_TRIGGER_TYPE;
import com.mot.rfid.api3.STATUS_EVENT_TYPE;
import com.mot.rfid.api3.STOP_TRIGGER_TYPE;
import com.mot.rfid.api3.SYSTEMTIME;
import com.mot.rfid.api3.TAG_EVENT_REPORT_TRIGGER;
import com.mot.rfid.api3.TagData;
import com.mot.rfid.api3.TriggerInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * stdin/stdout bridge that keeps the Zebra host SDK isolated from Electron.
 */
public class Bridge implements RfidEventsListener {

    private static final String EVENT_STATUS = "status";
    private static final String EVENT_TAG = "tag";
    private static final short[] DEFAULT_ANTENNAS = new short[]{1, 2, 3, 4};

    private final ObjectMapper mapper = new ObjectMapper();
    private final BridgeEmitter emitter;
    private final BridgeState state;
    private final BridgeConfig config;
    private final ScheduledExecutorService connector;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final Object readerLock = new Object();

    private RFIDReader reader;
    private ReaderManagement readerManagement;
    private TriggerInfo triggerInfo;
    private AntennaInfo antennaInfo;
    private ScheduledFuture<?> pendingConnect;

    public Bridge(PrintWriter out) {
        this.emitter = new BridgeEmitter(mapper, out);
        this.state = new BridgeState(DEFAULT_ANTENNAS);
        this.connector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "zebra-connector");
            thread.setDaemon(true);
            return thread;
        });
        this.config = BridgeConfig.load(mapper, System.getenv(), DEFAULT_ANTENNAS);
        emitStatus("boot", null);
        if (config.isValid()) {
            scheduleConnect(0);
        } else {
            state.setLastError("ZEBRA_READER_HOST is required");
            emitStatus("config-error", node -> node.put("missingHost", true));
        }
    }

    public static void main(String[] args) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(System.out, true)) {
            Bridge bridge = new Bridge(out);
            bridge.eventLoop(in);
        }
    }

    private void eventLoop(BufferedReader in) throws IOException {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    handleCommand(line.trim());
                } catch (Exception e) {
                    emitter.emitError(null, "Unhandled error: " + e.getMessage());
                }
            }
        } finally {
            shutdown("stdin-closed");
        }
    }

    private void handleCommand(String rawJson) throws JsonProcessingException {
        JsonNode root = mapper.readTree(rawJson);
        String commandId = root.path("id").asText(UUID.randomUUID().toString());
        String cmd = root.path("cmd").asText();

        switch (cmd) {
            case "ping" -> emitter.emitSuccess(commandId, "pong", buildStatusNode());
            case "mockTag" -> emitMockTag(commandId, root.path("leadId").asText(null));
            case "status" -> emitter.emitSuccess(commandId, EVENT_STATUS, buildStatusNode());
            case "inventory:start" -> {
                boolean started = startInventory("command");
                ObjectNode data = mapper.createObjectNode();
                data.put("running", started || state.isInventoryRunning());
                emitter.emitSuccess(commandId, "inventory", data);
            }
            case "inventory:stop" -> {
                boolean stopped = stopInventory("command");
                ObjectNode data = mapper.createObjectNode();
                data.put("running", stopped ? false : state.isInventoryRunning());
                emitter.emitSuccess(commandId, "inventory", data);
            }
            case "restart" -> {
                restartReader("command");
                emitter.emitSuccess(commandId, "restart", buildStatusNode());
            }
            case "shutdown" -> {
                emitter.emitSuccess(commandId, "shutdown", null);
                shutdown("command");
                System.exit(0);
            }
            default -> emitter.emitError(commandId, "Unknown cmd: " + cmd);
        }
    }

    @Override
    public void eventReadNotify(RfidReadEvents ignored) {
        RFIDReader current = reader;
        if (current == null) {
            return;
        }
        try {
            TagData[] tags = current.Actions.getReadTags(config.tagBatchSize);
            if (tags == null) {
                return;
            }
            for (TagData tag : tags) {
                if (tag != null) {
                    emitHardwareTag(tag);
                }
            }
        } catch (Exception e) {
            state.setLastError(e.getMessage());
            emitStatus("error", node -> node.put("message", "Tag read error: " + e.getMessage()));
        }
    }

    @Override
    public void eventStatusNotify(RfidStatusEvents events) {
        STATUS_EVENT_TYPE type = events.StatusEventData.getStatusEventType();
        if (type == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
            state.setInventoryRunning(true);
            emitStatus("inventory", node -> node.put("statusEvent", type.toString()));
        } else if (type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            state.setInventoryRunning(false);
            emitStatus("reader-online", node -> node.put("statusEvent", type.toString()));
        } else if (type == STATUS_EVENT_TYPE.READER_EXCEPTION_EVENT) {
            String info = events.StatusEventData.ReaderExceptionEventData != null
                ? events.StatusEventData.ReaderExceptionEventData.getReaderExceptionEventInfo()
                : "Reader exception";
            state.setLastError(info);
            emitStatus("error", node -> {
                node.put("statusEvent", type.toString());
                node.put("message", info);
            });
        } else if (type == STATUS_EVENT_TYPE.ANTENNA_EVENT) {
            String details = "";
            if (events.StatusEventData.AntennaEventData != null) {
                ANTENNA_EVENT_TYPE antennaEvent = events.StatusEventData.AntennaEventData.getAntennaEvent();
                int antennaId = events.StatusEventData.AntennaEventData.getAntennaID();
                details = antennaEvent + " #" + antennaId;
            }
            String finalDetails = details;
            emitStatus("reader-online", node -> {
                node.put("statusEvent", type.toString());
                if (!finalDetails.isEmpty()) {
                    node.put("details", finalDetails);
                }
            });
        } else if (type == STATUS_EVENT_TYPE.GPI_EVENT
            || type == STATUS_EVENT_TYPE.BUFFER_FULL_EVENT
            || type == STATUS_EVENT_TYPE.BUFFER_FULL_WARNING_EVENT
            || type == STATUS_EVENT_TYPE.TEMPERATURE_ALARM_EVENT) {
            emitStatus("reader-online", node -> node.put("statusEvent", type.toString()));
        } else if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            handleDisconnect(events);
        } else {
            emitStatus("status", node -> node.put("statusEvent", type.toString()));
        }
    }

    private void handleDisconnect(RfidStatusEvents events) {
        state.setInventoryRunning(false);
        state.setReaderConnected(false);
        state.setAuthenticated(false);
        String reason = "UNKNOWN";
        if (events.StatusEventData.DisconnectionEventData != null) {
            DISCONNECTION_EVENT_TYPE eventType = events.StatusEventData.DisconnectionEventData.getDisconnectionEvent();
            if (eventType != null) {
                reason = eventType.toString();
            }
        }
        String finalReason = reason;
        emitStatus("disconnected", node -> node.put("reason", finalReason));
        disconnectInternal();
        scheduleConnect(config.reconnectDelayMs);
    }

    private void emitHardwareTag(TagData tag) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tagId", tag.getTagID());
        payload.put("timestamp", toIsoString(tag.getTagEventTimeStamp()));
        payload.put("antenna", tag.getAntennaID());
        payload.put("rssi", tag.getPeakRSSI());
        payload.put("seenCount", tag.getTagSeenCount());
        payload.put("mock", false);
        payload.put("bridge", true);
        payload.put("source", "bridge");

        ObjectNode raw = mapper.createObjectNode();
        raw.put("pc", tag.getPC());
        raw.put("xpcW1", tag.getXPC_W1());
        raw.put("xpcW2", tag.getXPC_W2());
        raw.put("crc", tag.getCRC());
        raw.put("phase", tag.getPhase());
        raw.put("channelIndex", tag.getChannelIndex());
        raw.put("opCode", tag.getOpCode() != null ? tag.getOpCode().toString() : null);
        raw.put("opStatus", tag.getOpStatus() != null ? tag.getOpStatus().toString() : null);
        raw.put("memoryBank", tag.getMemoryBank() != null ? tag.getMemoryBank().toString() : null);
        raw.put("memoryBankData", tag.getMemoryBankData());
        raw.put("memoryBankDataOffset", tag.getMemoryBankDataOffset());
        if (tag.getTagEvent() != null) {
            raw.put("tagEvent", tag.getTagEvent().toString());
        }
        SYSTEMTIME tagTime = tag.getTagEventTimeStamp();
        if (tagTime != null) {
            raw.put("tagEventTimestamp", toIsoString(tagTime));
        }
        payload.set("raw", raw);

        emitter.emitPush(EVENT_TAG, payload);
    }

    private void emitMockTag(String commandId, String leadId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tagId", "SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        payload.put("timestamp", Instant.now().toString());
        payload.put("mock", true);
        payload.put("source", "mock");
        payload.put("bridge", false);
        if (leadId != null && !leadId.isBlank()) {
            payload.put("leadId", leadId);
        }
        emitter.emitSuccess(commandId, EVENT_TAG, payload);
    }

    private void scheduleConnect(long delayMs) {
        long delay = Math.max(0, delayMs);
        synchronized (readerLock) {
            if (shuttingDown.get()) {
                return;
            }
            if (pendingConnect != null && !pendingConnect.isDone()) {
                pendingConnect.cancel(true);
            }
            pendingConnect = connector.schedule(this::connectOnce, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void connectOnce() {
        synchronized (readerLock) {
            pendingConnect = null;
        }
        if (shuttingDown.get() || state.isReaderConnected()) {
            return;
        }
        emitStatus("connecting", null);
        try {
            connectInternal();
            emitStatus("reader-online", null);
            if (config.autoStartInventory) {
                startInventory("auto");
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            handleConnectFailure(e);
        } catch (RuntimeException e) {
            handleConnectFailure(e);
        }
    }

    private void handleConnectFailure(Exception e) {
        state.setLastError(e.getMessage());
        String message = state.getLastError() == null ? e.toString() : state.getLastError();
        emitStatus("error", node -> node.put("message", message));
        scheduleConnect(config.reconnectDelayMs);
    }

    private void connectInternal() throws InvalidUsageException, OperationFailureException {
        RFIDReader newReader = new RFIDReader();
        ReaderManagement newManager = null;
        LoginInfo loginInfo = null;
        boolean loginSuccess = false;
        try {
            newReader.setHostName(config.host);
            newReader.setPort(config.port);
            if (config.connectTimeoutMs > 0) {
                newReader.setTimeout(config.connectTimeoutMs);
            }
            System.err.println("[rfid-bridge] Connecting to reader " + config.host + ":" + config.port);
            newReader.connect();
            System.err.println("[rfid-bridge] Connected to reader.");
            prepareReaderEvents(newReader);

            if (config.shouldLogin()) {
                newManager = new ReaderManagement();
                loginInfo = new LoginInfo();
                loginInfo.setHostName(config.host);
                loginInfo.setUserName(config.username);
                loginInfo.setPassword(config.password == null ? "" : config.password);
                loginInfo.setSecureMode(config.secureMode);
                loginInfo.setForceLogin(config.forceLogin);
                System.err.println("[rfid-bridge] Attempting reader login (force=" + config.forceLogin + ", secureMode=" + config.secureMode + ").");
                newManager.login(loginInfo, config.readerType);
                loginSuccess = true;
                System.err.println("[rfid-bridge] Reader login succeeded.");
            }

            TriggerInfo newTrigger = createDefaultTrigger();
            AntennaInfo newAntenna = createAntennaInfo(newReader);
            short[] antennas = newAntenna.getAntennaID();
            ObjectNode metadata = buildReaderDetails(newReader, antennas);

            synchronized (readerLock) {
                internalDisconnect();
                reader = newReader;
                reader.Events.addEventsListener(this);
                readerManagement = newManager;
                triggerInfo = newTrigger;
                antennaInfo = newAntenna;
                state.setActiveAntennas(antennas != null ? antennas : DEFAULT_ANTENNAS);
                state.setReaderMetadata(metadata);
                state.setReaderConnected(true);
                state.setAuthenticated(loginSuccess);
                state.setInventoryRunning(false);
                state.setLastError(null);
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            System.err.println("[rfid-bridge] Reader connect/login failed: " + e.getMessage());
            System.err.println("[rfid-bridge] Exception class: " + e.getClass().getName());
            System.err.println("[rfid-bridge] Stack trace:");
            e.printStackTrace(System.err);
            if (e.getCause() != null) {
                System.err.println("[rfid-bridge] Caused by: " + e.getCause().getMessage());
                e.getCause().printStackTrace(System.err);
            }
            safeDisconnect(newReader, newManager);
            throw e;
        } catch (RuntimeException e) {
            System.err.println("[rfid-bridge] Unexpected error during connect: " + e.getMessage());
            System.err.println("[rfid-bridge] Stack trace:");
            e.printStackTrace(System.err);
            safeDisconnect(newReader, newManager);
            throw e;
        }
    }

    private void safeDisconnect(RFIDReader targetReader, ReaderManagement targetManager) {
        try {
            if (targetReader != null) {
                targetReader.Events.removeEventsListener(this);
            }
        } catch (Exception ignored) {
        }
        try {
            if (targetReader != null) {
                targetReader.disconnect();
            }
        } catch (Exception ignored) {
        }
        try {
            if (targetManager != null) {
                targetManager.logout();
            }
        } catch (Exception ignored) {
        }
        try {
            if (targetManager != null) {
                targetManager.dispose();
            }
        } catch (Exception ignored) {
        }
    }

    private void prepareReaderEvents(RFIDReader target) {
        target.Events.setInventoryStartEvent(true);
        target.Events.setInventoryStopEvent(true);
        target.Events.setAccessStartEvent(true);
        target.Events.setAccessStopEvent(true);
        target.Events.setAntennaEvent(true);
        target.Events.setGPIEvent(true);
        target.Events.setBufferFullEvent(true);
        target.Events.setBufferFullWarningEvent(true);
        target.Events.setReaderDisconnectEvent(true);
        target.Events.setReaderExceptionEvent(true);
        target.Events.setTagReadEvent(true);
        target.Events.setAttachTagDataWithReadEvent(false);
        target.Events.setTemperatureAlarmEvent(true);
    }

    private TriggerInfo createDefaultTrigger() {
        TriggerInfo info = new TriggerInfo();
        info.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        info.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
        info.TagEventReportInfo.setReportNewTagEvent(TAG_EVENT_REPORT_TRIGGER.MODERATED);
        info.TagEventReportInfo.setNewTagEventModeratedTimeoutMilliseconds((short) 500);
        info.TagEventReportInfo.setReportTagInvisibleEvent(TAG_EVENT_REPORT_TRIGGER.MODERATED);
        info.TagEventReportInfo.setTagInvisibleEventModeratedTimeoutMilliseconds((short) 500);
        info.TagEventReportInfo.setReportTagBackToVisibilityEvent(TAG_EVENT_REPORT_TRIGGER.MODERATED);
        info.TagEventReportInfo.setTagBackToVisibilityModeratedTimeoutMilliseconds((short) 500);
        info.setTagReportTrigger(1);
        return info;
    }

    private AntennaInfo createAntennaInfo(RFIDReader target) {
        short[] configured = config.antennas;
        short[] values;
        if (configured != null && configured.length > 0) {
            values = Arrays.copyOf(configured, configured.length);
        } else {
            values = discoverAntennas(target);
        }
        return new AntennaInfo(values);
    }

    private short[] discoverAntennas(RFIDReader target) {
        return Arrays.copyOf(DEFAULT_ANTENNAS, DEFAULT_ANTENNAS.length);
    }

    private boolean startInventory(String reason) {
        RFIDReader current;
        TriggerInfo trigger;
        AntennaInfo antennas;
        synchronized (readerLock) {
            current = reader;
            trigger = triggerInfo;
            antennas = antennaInfo;
        }
        if (current == null || trigger == null || antennas == null) {
            state.setLastError("Reader not ready");
            emitStatus("error", node -> node.put("message", state.getLastError()));
            return false;
        }
        try {
            current.Actions.purgeTags();
            current.Actions.Inventory.perform(null, trigger, antennas);
            state.setInventoryRunning(true);
            emitStatus("inventory", node -> node.put("startedBy", reason));
            return true;
        } catch (InvalidUsageException | OperationFailureException e) {
            state.setLastError(e.getMessage());
            String message = state.getLastError() == null ? e.toString() : state.getLastError();
            emitStatus("error", node -> node.put("message", message));
            return false;
        }
    }

    private boolean stopInventory(String reason) {
        RFIDReader current = reader;
        if (current == null || !state.isInventoryRunning()) {
            return false;
        }
        try {
            current.Actions.Inventory.stop();
            state.setInventoryRunning(false);
            emitStatus("reader-online", node -> node.put("stoppedBy", reason));
            return true;
        } catch (InvalidUsageException | OperationFailureException e) {
            state.setLastError(e.getMessage());
            String message = state.getLastError() == null ? e.toString() : state.getLastError();
            emitStatus("error", node -> node.put("message", message));
            return false;
        }
    }

    private void restartReader(String reason) {
        disconnectInternal();
        emitStatus("waiting", node -> node.put("reason", reason));
        scheduleConnect(0);
    }

    private void disconnectInternal() {
        synchronized (readerLock) {
            internalDisconnect();
        }
    }

    private void internalDisconnect() {
        if (reader != null) {
            try {
                reader.Events.removeEventsListener(this);
            } catch (Exception ignored) {
            }
            if (state.isInventoryRunning()) {
                try {
                    reader.Actions.Inventory.stop();
                } catch (Exception ignored) {
                }
            }
            try {
                reader.disconnect();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        if (readerManagement != null) {
            try {
                if (state.isAuthenticated()) {
                    readerManagement.logout();
                }
            } catch (Exception ignored) {
            }
            try {
                readerManagement.dispose();
            } catch (Exception ignored) {
            }
            readerManagement = null;
        }
        triggerInfo = null;
        antennaInfo = null;
        state.setReaderMetadata(null);
        state.resetAntennaDefaults(DEFAULT_ANTENNAS);
        state.setReaderConnected(false);
        state.setAuthenticated(false);
        state.setInventoryRunning(false);
    }

    private void shutdown(String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        emitStatus("stopping", node -> node.put("reason", reason));
        synchronized (readerLock) {
            if (pendingConnect != null) {
                pendingConnect.cancel(true);
                pendingConnect = null;
            }
        }
        connector.shutdownNow();
        internalDisconnect();
    }

    private ObjectNode buildReaderDetails(RFIDReader target, short[] antennas) {
        ObjectNode node = mapper.createObjectNode();
        node.put("host", config.host);
        node.put("port", config.port);
        node.put("readerType", config.readerType.toString());
        if (antennas != null && antennas.length > 0) {
            ArrayNode array = mapper.createArrayNode();
            for (short antenna : antennas) {
                array.add(antenna);
            }
            node.set("antennas", array);
        }
        return node;
    }

    private ObjectNode buildStatusNode() {
        ObjectNode data = mapper.createObjectNode();
        data.put("readerConnected", state.isReaderConnected());
        data.put("inventoryRunning", state.isInventoryRunning());
        data.put("authenticated", state.isAuthenticated());
        data.put("host", config.host);
        data.put("port", config.port);
        data.put("autoStartInventory", config.autoStartInventory);
        data.put("reconnectDelayMs", config.reconnectDelayMs);
        data.put("connectTimeoutMs", config.connectTimeoutMs);
        data.put("readerType", config.readerType.toString());
        data.put("secureMode", config.secureMode.toString());
        short[] antennas = state.getActiveAntennas();
        if (antennas != null && antennas.length > 0) {
            ArrayNode array = mapper.createArrayNode();
            for (short antenna : antennas) {
                array.add(antenna);
            }
            data.set("antennas", array);
        }
        ObjectNode metadata = state.getReaderMetadata();
        if (metadata != null) {
            data.set("reader", metadata);
        }
        if (state.getLastError() != null) {
            data.put("lastError", state.getLastError());
        }
        return data;
    }

    private void emitStatus(String phase, Consumer<ObjectNode> customizer) {
        ObjectNode data = buildStatusNode();
        data.put("phase", phase);
        if (customizer != null) {
            customizer.accept(data);
        }
        emitter.emitPush(EVENT_STATUS, data);
    }

    private String toIsoString(SYSTEMTIME time) {
        if (time == null || time.Year == 0 || time.Month == 0 || time.Day == 0) {
            return Instant.now().toString();
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.of(
                Math.max(time.Year, 1970),
                Math.max(time.Month, 1),
                Math.max(time.Day, 1),
                Math.max(time.Hour, 0),
                Math.max(time.Minute, 0),
                Math.max(time.Second, 0),
                Math.max(time.Milliseconds, 0) * 1_000_000
            );
            return localDateTime.atOffset(ZoneOffset.UTC).toInstant().toString();
        } catch (Exception e) {
            return Instant.now().toString();
        }
    }
}
