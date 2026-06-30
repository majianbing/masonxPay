package com.masonx.rail.iso20022;

public enum Iso20022MessageType {
    PAIN_001,  // credit transfer initiation (sent by rail-service)
    PAIN_002,  // payment status report (received from bank-sim)
    PACS_002,  // FI payment status — settlement or rejection
    PACS_004,  // payment return
    CAMT_054   // bank-to-customer debit/credit notification
}
