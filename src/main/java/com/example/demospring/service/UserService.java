package com.example.demospring.service;

import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.UserRepository;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    public List<User> getAll(){
        return userRepository.findAll();
    }

    public User saveUser(User user){
         return userRepository.save(user);
    }

    public void deleteUser(ObjectId id){

        userRepository.deleteById(id);
        return ;
    }

    public Optional<User> getById(ObjectId id){
        return userRepository.findById(id);
    }

    public boolean updatePassword(ObjectId id, String newPassword) {
        Optional<User> user = userRepository.findById(id);

        if (user.isPresent()) {
            User usr = user.get();
            usr.setPassword(newPassword); // Remember to encode this password later!
            userRepository.save(usr);
            return true; // Update successful
        }

        return false; // User not found
    }

    public List<DemoEntry> listAllDemos(ObjectId id){
        Optional<User> user = userRepository.findById(id);

        return user.map(User::getDemoEntries).orElse(null);
        /*
        if(user.isPresent()){
            return user.get().getDemoEntries();
        }
        return null;
        */
    }

}
