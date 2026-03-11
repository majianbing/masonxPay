package com.masonx.paygateway.domain.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationUserRepository extends JpaRepository<OrganizationUser, UUID> {

    @Query("SELECT ou FROM OrganizationUser ou JOIN FETCH ou.organization WHERE ou.user.id = :userId AND ou.status = 'ACTIVE'")
    List<OrganizationUser> findActiveByUserId(UUID userId);

    Optional<OrganizationUser> findByUser_IdAndOrganization_Id(UUID userId, UUID organizationId);
}
