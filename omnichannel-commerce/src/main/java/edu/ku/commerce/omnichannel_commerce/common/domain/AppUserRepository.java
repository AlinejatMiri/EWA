package edu.ku.commerce.omnichannel_commerce.common.domain;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface AppUserRepository extends JpaRepository<AppUser,Long>{ Optional<AppUser> findByEmail(String email); boolean existsByEmail(String email); }
