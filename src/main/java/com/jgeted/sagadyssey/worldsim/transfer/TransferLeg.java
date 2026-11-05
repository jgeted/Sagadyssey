package com.jgeted.sagadyssey.worldsim.transfer;

import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;

import java.util.UUID;

public record TransferLeg(UUID sourceContainerId, UUID destinationContainerId,
                          ItemFingerprint fingerprint, int quantity) {
    public TransferLeg {
        if (sourceContainerId == null || destinationContainerId == null || fingerprint == null
                || sourceContainerId.equals(destinationContainerId) || quantity <= 0) {
            throw new IllegalArgumentException("invalid transfer leg");
        }
    }
}
