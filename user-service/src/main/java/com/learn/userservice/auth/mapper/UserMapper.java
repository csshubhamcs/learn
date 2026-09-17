package com.learn.userservice.auth.mapper;

import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Pure field mapping; email now lives directly on {@link User}. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    @Mapping(target = "status", expression = "java(user.getStatus().name())")
    UserResponse toResponse(User user);
}
