package pods.marketplace.user_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

// setting the class as entity
@Entity
@Data
public class Users {
    // sets as primary key
    @Id
    @Column(nullable = false)
    private Integer id;

    // string val can be null ?? why
    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    // @Column(nullable = false)
    private Boolean discount_availed = false;

}
