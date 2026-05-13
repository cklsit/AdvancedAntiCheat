package com.anticheat.utils;

import com.anticheat.profiles.PlayerProfile;

import java.io.*;
import java.util.Base64;

public class ProfileSerializer {
    
    public static String serialize(PlayerProfile profile) throws IOException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(profile);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
    
    public static PlayerProfile deserialize(String data) throws IOException, ClassNotFoundException {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        byte[] bytes = Base64.getDecoder().decode(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (PlayerProfile) ois.readObject();
        }
    }
}