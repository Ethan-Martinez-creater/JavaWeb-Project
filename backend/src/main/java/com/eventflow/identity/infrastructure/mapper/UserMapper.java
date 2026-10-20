package com.eventflow.identity.infrastructure.mapper;

import com.eventflow.identity.domain.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
  UserEntity findByEmail(@Param("email") String email);

  int insertUser(UserEntity user);

  int insertRole(@Param("userId") long userId, @Param("role") String role);

  List<String> findRolesByUserId(@Param("userId") long userId);
}
