package com.learn.userservice.profile.service;

import com.learn.userservice.profile.dto.request.UpdateProfileRequest;
import com.learn.userservice.profile.dto.response.ProfileResponse;
import java.util.UUID;

/** Reads and patches the single profile row that exists (or lazily comes to exist) per user. */
public interface ProfileService {

    /**
     * Returns the caller's profile, creating an empty one first if this is their first visit.
     * A caller with no active account here gets 404, not a lazily created orphan profile.
     */
    ProfileResponse getOrCreate(UUID userId);

    /** Applies only the non-null fields of {@code request}; null fields are left untouched. */
    ProfileResponse update(UUID userId, UpdateProfileRequest request);
}
