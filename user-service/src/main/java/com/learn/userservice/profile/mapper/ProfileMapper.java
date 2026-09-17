package com.learn.userservice.profile.mapper;

import com.learn.userservice.profile.dto.request.UpdateProfileRequest;
import com.learn.userservice.profile.dto.response.ProfileResponse;
import com.learn.userservice.profile.model.UserProfile;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between {@link UserProfile} and its DTOs. {@code unmappedTargetPolicy = ERROR}
 * makes the build fail the moment a field is added to the entity or a DTO without a
 * matching counterpart on the other side, which is what keeps this mapper trustworthy
 * without anyone having to remember to update it by hand.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProfileMapper {

    // xUrl needs an explicit @Mapping on both methods: the JavaBean spec decapitalizes a
    // getter/setter name by lower-casing only its first letter, UNLESS the first two letters
    // are both upper case, in which case it leaves the name alone. getXUrl()/setXUrl() hit
    // that rule, so bean introspection sees the entity's property as "XUrl", not "xUrl" -
    // which does not auto-match the DTOs' "xUrl" record component and would otherwise trip
    // unmappedTargetPolicy = ERROR in both directions.

    /** Renders the full entity for a GET or the result of a PATCH. */
    @Mapping(target = "xUrl", source = "XUrl")
    ProfileResponse toResponse(UserProfile profile);

    /**
     * PATCH semantics: a null field means "leave unchanged". IGNORE gives us exactly that,
     * and it means adding a profile field never requires touching this mapper again. userId
     * is the entity's identity, set once at creation, never part of the patch payload.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "XUrl", source = "xUrl")
    void applyPatch(UpdateProfileRequest request, @MappingTarget UserProfile profile);
}
