package com.learn.userservice.profile.service.impl;

import com.learn.userservice.auth.service.ActiveUserGuard;
import com.learn.userservice.profile.dto.request.UpdateProfileRequest;
import com.learn.userservice.profile.dto.response.ProfileResponse;
import com.learn.userservice.profile.mapper.ProfileMapper;
import com.learn.userservice.profile.model.UserProfile;
import com.learn.userservice.profile.repository.UserProfileRepository;
import com.learn.userservice.profile.service.ProfileService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class ProfileServiceImpl implements ProfileService {

    private final UserProfileRepository profiles;
    private final ProfileMapper mapper;
    private final ActiveUserGuard activeUser;

    @Override
    @Transactional
    public ProfileResponse getOrCreate(UUID userId) {
        return mapper.toResponse(loadOrCreate(userId));
    }

    @Override
    @Transactional
    public ProfileResponse update(UUID userId, UpdateProfileRequest request) {
        UserProfile profile = loadOrCreate(userId);
        mapper.applyPatch(request, profile);
        return mapper.toResponse(profiles.save(profile));
    }

    /**
     * Profiles are created lazily so registration stays a single concern - but the lazy
     * insert is keyed on the JWT subject and fk_profile_user requires a matching users row,
     * so a principal with no local account (realm import, admin console, a self-deleted user
     * whose token has not expired yet) has to be turned away before the insert, not by the
     * integrity violation it would otherwise cause at flush.
     */
    private UserProfile loadOrCreate(UUID userId) {
        activeUser.requireActive(userId);
        return profiles.findById(userId).orElseGet(() -> {
            UserProfile created = new UserProfile();
            created.setUserId(userId);
            return profiles.save(created);
        });
    }
}
