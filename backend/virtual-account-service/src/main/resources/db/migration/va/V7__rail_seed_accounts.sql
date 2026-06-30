-- MR4: platform and external accounts for rail settlement journals.
-- These accounts are referenced by CardRailSettlementHandler when posting journals
-- for card sale, bank transfer, card reversal, and bank return events.
--
-- EXTERNAL accounts (AccountRole=EXTERNAL) represent card networks and bank rails.
-- PLATFORM account (AccountRole=PLATFORM) is for tracking unresolved unknown card transactions.
-- All seeded in TEST mode for the simulator lab.
--
-- Uses ON CONFLICT DO NOTHING so re-running (e.g. local reset) is safe.

INSERT INTO va_account (
    account_id, mode, account_role, org_id, merchant_id, provider_id,
    account_type, asset, asset_class, scale, normal_balance,
    balance, frozen_balance, status
) VALUES
-- VISA_SIM card network receivable
('va_rail_visa_rcv',    'TEST', 'EXTERNAL', NULL, NULL, 'VISA_SIM',
 'CARD_NETWORK_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 0, 'ACTIVE'),

-- MC_SIM card network receivable
('va_rail_mc_rcv',      'TEST', 'EXTERNAL', NULL, NULL, 'MC_SIM',
 'CARD_NETWORK_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 0, 'ACTIVE'),

-- SEPA_SIM bank rail receivable
('va_rail_sepa_rcv',    'TEST', 'EXTERNAL', NULL, NULL, 'SEPA_SIM',
 'BANK_RAIL_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 0, 'ACTIVE'),

-- FEDNOW_SIM bank rail receivable
('va_rail_fednow_rcv',  'TEST', 'EXTERNAL', NULL, NULL, 'FEDNOW_SIM',
 'BANK_RAIL_RECEIVABLE', 'USD', 'FIAT', 2, 'DEBIT', 0, 0, 'ACTIVE'),

-- Platform suspense account for card transactions with unknown outcome (timeout + reversal pending)
('va_rail_card_suspense', 'TEST', 'PLATFORM', NULL, NULL, NULL,
 'SUSPENSE_UNKNOWN_TXN', 'USD', 'FIAT', 2, 'DEBIT', 0, 0, 'ACTIVE')

ON CONFLICT (account_id) DO NOTHING;
