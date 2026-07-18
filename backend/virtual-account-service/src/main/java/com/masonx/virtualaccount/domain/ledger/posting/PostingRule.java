package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;

import java.util.List;

public interface PostingRule<T> {
    List<LedgerPostingCommand> build(T event);
}
