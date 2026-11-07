package org.myweb.flowmat.domain.user.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.repository.FaceDescriptorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FaceFailureService {

    private final FaceDescriptorRepository faceDescriptorRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long faceId) {
        faceDescriptorRepository.findById(faceId).ifPresent(descriptor -> {
            descriptor.recordFailure();
            faceDescriptorRepository.save(descriptor);
        });
    }
}
