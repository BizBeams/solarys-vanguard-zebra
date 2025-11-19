package com.solarys.rfid;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;

final class BridgeState {
    private volatile boolean readerConnected;
    private volatile boolean authenticated;
    private volatile boolean inventoryRunning;
    private volatile String lastError;
    private volatile short[] activeAntennas;
    private volatile ObjectNode readerMetadata;

    BridgeState(short[] defaultAntennas) {
        setActiveAntennas(defaultAntennas);
    }

    boolean isReaderConnected() {
        return readerConnected;
    }

    void setReaderConnected(boolean readerConnected) {
        this.readerConnected = readerConnected;
    }

    boolean isAuthenticated() {
        return authenticated;
    }

    void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    boolean isInventoryRunning() {
        return inventoryRunning;
    }

    void setInventoryRunning(boolean inventoryRunning) {
        this.inventoryRunning = inventoryRunning;
    }

    String getLastError() {
        return lastError;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    short[] getActiveAntennas() {
        short[] snapshot = activeAntennas;
        return snapshot == null ? null : Arrays.copyOf(snapshot, snapshot.length);
    }

    synchronized void setActiveAntennas(short[] antennas) {
        if (antennas == null) {
            this.activeAntennas = null;
            return;
        }
        this.activeAntennas = Arrays.copyOf(antennas, antennas.length);
    }

    ObjectNode getReaderMetadata() {
        return readerMetadata == null ? null : readerMetadata.deepCopy();
    }

    synchronized void setReaderMetadata(ObjectNode metadata) {
        this.readerMetadata = metadata == null ? null : metadata.deepCopy();
    }

    void resetAntennaDefaults(short[] defaults) {
        setActiveAntennas(defaults);
    }
}
