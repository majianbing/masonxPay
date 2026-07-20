-- MR4: platform and external accounts for rail settlement journals.
-- These accounts are referenced by CardRailSettlementHandler when posting journals
-- for card sale, bank transfer, card reversal, and bank return events.
--
-- EXTERNAL accounts (LedgerAccountRole=EXTERNAL) represent card networks and bank rails.
-- PLATFORM account (LedgerAccountRole=PLATFORM) is for tracking unresolved unknown card transactions.
-- All seeded in TEST mode for the simulator lab.
--
-- Uses ON CONFLICT DO NOTHING so re-running (e.g. local reset) is safe.

INSERT INTO ledger_account (
    ledger_account_id, mode, ledger_account_role, org_id, merchant_id, provider_id,
    ledger_account_type, asset, asset_class, scale, normal_balance,
    balance, status
) VALUES
-- VISA_SIM card network settlement account. CREDIT-normal: on the ISSUING side
-- the platform owes the network when its card is used (payable semantics; the
-- CARD_NETWORK_RECEIVABLE type name is retained until the CoA cleanup).
('va_rail_visa_rcv',    'TEST', 'EXTERNAL', NULL, NULL, 'VISA_SIM',
 'CARD_NETWORK_RECEIVABLE', 'USD', 'FIAT', 2, 'CREDIT', 0, 'ACTIVE'),

-- MC_SIM card network settlement account (same convention as VISA_SIM).
('va_rail_mc_rcv',      'TEST', 'EXTERNAL', NULL, NULL, 'MC_SIM',
 'CARD_NETWORK_RECEIVABLE', 'USD', 'FIAT', 2, 'CREDIT', 0, 'ACTIVE'),

-- SEPA_SIM bank rail receivable
('va_rail_sepa_rcv',    'TEST', 'EXTERNAL', NULL, NULL, 'SEPA_SIM',
 'BANK_RAIL_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 'ACTIVE'),

-- FEDNOW_SIM bank rail receivable
('va_rail_fednow_rcv',  'TEST', 'EXTERNAL', NULL, NULL, 'FEDNOW_SIM',
 'BANK_RAIL_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 'ACTIVE'),

-- Platform suspense account for card transactions with unknown outcome (timeout + reversal pending)
('va_rail_card_suspense', 'TEST', 'PLATFORM', NULL, NULL, NULL,
 'SUSPENSE_UNKNOWN_TXN', 'USD', 'FIAT', 2, 'DEBIT', 0, 'ACTIVE')

ON CONFLICT (ledger_account_id) DO NOTHING;
