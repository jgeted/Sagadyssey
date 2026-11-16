package com.jgeted.sagadyssey.worldsim.transfer;

/** RELOCATE only rearranges one custodian's assets without changing title or loan provenance. */
public enum TransferIntent {
    GIFT, ISSUE, DELIVERY, PAYMENT, LOAN, RETURN, THEFT, RELOCATE
}
