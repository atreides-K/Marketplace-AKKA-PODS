package pods.marketplace.user_service;

import pods.marketplace.user_service.model.Users;

import java.util.List;


import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<Users, Integer> {
    List<Users> findByEmail(String email);
}
