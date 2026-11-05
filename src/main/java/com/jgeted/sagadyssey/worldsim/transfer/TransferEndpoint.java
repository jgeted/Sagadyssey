package com.jgeted.sagadyssey.worldsim.transfer;

import net.minecraft.world.Container;

import java.util.UUID;

public record TransferEndpoint(UUID id, Container container) {
    public TransferEndpoint {
        if (id == null || container == null) throw new IllegalArgumentException("invalid endpoint");
    }
}
