package pods.marketplace.user_service;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import pods.marketplace.user_service.model.Users;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;



@RestController
// setting the base path for the userservice
@RequestMapping("/users")


public class UserController {
    
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private UserRepository userRepository;

    private static final String MARKETPLACE_SERVICE_URL = "http://host.docker.internal:8081/"; 
    private static final String WALLET_SERVICE_URL = "http://host.docker.internal:8082/";

    @Transactional
    @PostMapping(value="",consumes = "application/json")
    public ResponseEntity<Users> createUser(@RequestBody Users user){
        // 1. check email exists
        if(!userRepository.findByEmail(user.getEmail()).isEmpty()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"email exists");
        };

        // 2. userid Exists
        if(userRepository.findById(user.getId()).isPresent()){
            System.out.println("user exist");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"user exists");
        }
        
        // 3. saving the user object in the database
        Users savedUser=userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedUser);
     
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Users> getUserById(@PathVariable Integer userId){

        Users user=userRepository.findById(userId).orElseThrow(()-> new ResponseStatusException(HttpStatus.NOT_FOUND,"user not found"));

        return ResponseEntity.ok(user);

    }

    @Transactional
    @PutMapping(value="/{userId}/discount",consumes = "application/json")
    public ResponseEntity<Void> updateUser(@RequestBody Users body){
        // 1. check discount_availed is true
        if(!body.getDiscount_availed()){
            return ResponseEntity.ok().build();
        }

        // 2. check user exist  
        Users user=getUser(body.getId());

        // 3. updating the discount_availed 
        user.setDiscount_availed(body.getDiscount_availed());
        userRepository.save(user);
        
        return ResponseEntity.ok().build();

    }
    

    @Transactional
        @DeleteMapping("/{userId}")
    public ResponseEntity<Users> deleteUserById(@PathVariable Integer userId){
        
        // 1. Check user exists
        System.out.println("Fetching user with ID: " + userId);
        getUser(userId);
        
        System.out.println("Cancelling orders for user with ID: " + userId);
        try {
            restTemplate.delete(MARKETPLACE_SERVICE_URL + "/marketplace/users/" + userId);
        } catch (HttpClientErrorException.NotFound e) {
            System.out.println("No active orders found for user " + userId + ", proceeding with deletion.");
        }
        
        System.out.println("Removing wallet for user with ID: " + userId);
        try {
            restTemplate.delete(WALLET_SERVICE_URL + "/wallets/" + userId);
        } catch (HttpClientErrorException.NotFound e) {
            System.out.println("No wallet found for user " + userId + ", proceeding with deletion.");
        }
        
        System.out.println("Deleting user with ID: " + userId);
        userRepository.deleteById(userId);

        return new ResponseEntity<>(HttpStatus.OK);
    }


    @Transactional
    @DeleteMapping("")
    public ResponseEntity<Users> deleteUsers(){
        
        // 1. reset marketplace
        System.out.println("Resetting marketplace service...");
        restTemplate.delete(MARKETPLACE_SERVICE_URL+"/marketplace");
        
        // 2. reset wallet
        System.out.println("Resetting wallet service...");
        restTemplate.delete(WALLET_SERVICE_URL+"/wallets");

        // 3. delete all user entities !!!
        System.out.println("Deleting all user entities...");
        userRepository.deleteAllInBatch();

        System.out.println("All users deleted successfully.");
        return new ResponseEntity<>(HttpStatus.OK);
    }
    
    // Service/Utility functions
    private Users getUser(Integer id){
        
        Users user=userRepository.findById(id).orElseThrow(()-> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return user;
    }

    @GetMapping("")
    public ResponseEntity<List<Users>> getAllUsers() {
        List<Users> users = userRepository.findAll();
        return ResponseEntity.ok(users);
    }
    

}
