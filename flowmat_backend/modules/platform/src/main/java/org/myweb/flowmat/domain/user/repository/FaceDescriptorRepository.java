package org.myweb.flowmat.domain.user.repository;

import java.util.Optional;
import org.myweb.flowmat.domain.user.domain.entity.FaceDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceDescriptorRepository extends JpaRepository<FaceDescriptor, Long> {

    Optional<FaceDescriptor> findByUserId(String userId);
}
