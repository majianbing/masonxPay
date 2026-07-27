package com.masonx.virtualaccount.vcc;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.vcc.dto.CardAuthorizationResponse;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CardAuthorizationQueryService {

    private final CardAuthorizationRepository authorizationRepo;

    public CardAuthorizationQueryService(CardAuthorizationRepository authorizationRepo) {
        this.authorizationRepo = authorizationRepo;
    }

    public PagedResult<CardAuthorizationResponse> list(String merchantId, Mode mode, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<CardAuthorizationResponse> content = authorizationRepo
                .findByMerchant(merchantId, mode.name(), safePage, safeSize)
                .stream()
                .map(CardAuthorizationResponse::from)
                .toList();
        long total = authorizationRepo.countByMerchant(merchantId, mode.name());
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / safeSize);
        return new PagedResult<>(content, safePage, safeSize, total, totalPages);
    }
}
