package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;

import java.util.UUID;

public record TransferLeg(UUID sourceContainerId, UUID destinationContainerId,
                          ItemFingerprint fingerprint, int quantity, int sourceSlot, int destinationSlot) {
    public TransferLeg(UUID source, UUID destination, ItemFingerprint fingerprint, int quantity) {
        this(source, destination, fingerprint, quantity, -1, -1);
    }
    public TransferLeg {
        if (sourceContainerId == null || destinationContainerId == null || fingerprint == null
                || sourceContainerId.equals(destinationContainerId) || quantity <= 0
                || sourceSlot < -1 || sourceSlot > 4095 || destinationSlot < -1 || destinationSlot > 4095) {
            throw new IllegalArgumentException("invalid transfer leg");
        }
    }
}
