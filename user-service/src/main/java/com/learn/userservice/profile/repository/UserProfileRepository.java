package com.learn.userservice.profile.repository;

import com.learn.userservice.profile.model.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Plain CRUD access to {@link UserProfile}; the id is the Keycloak subject (see the entity). */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {}
