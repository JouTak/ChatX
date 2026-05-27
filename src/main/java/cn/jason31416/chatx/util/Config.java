package cn.jason31416.chatx.util;

import cn.jason31416.chatx.ChatX;
import lombok.Getter;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Objects;

public class Config {
    @Getter
    private static MapTree configTree;
    @Getter
    private static MapTree channelTree;

    public static void init() {
        if(!ChatX.getDataDirectory().exists()) ChatX.getDataDirectory().mkdirs();
        File config = new File(ChatX.getDataDirectory(), "config.yml");
        if(!config.exists()){
            try (InputStream is = ChatX.class.getClassLoader().getResourceAsStream("config.yml"); OutputStream os = new FileOutputStream(config)) {
                Objects.requireNonNull(is).transferTo(os);
            }catch (Exception e){
                Logger.error("Cannot save config file!");
                throw new RuntimeException(e);
            }
        }

        try(InputStream inputStream = new FileInputStream(config)) {
            configTree = new MapTree(new Yaml().load(inputStream));
        }catch (Exception e){
            Logger.error("Failed to load config.yml: " + e.getMessage());
        }

        File channel = new File(ChatX.getDataDirectory(), "channel.yml");
        if(!channel.exists()){
            try (InputStream is = ChatX.class.getClassLoader().getResourceAsStream("channel.yml"); OutputStream os = new FileOutputStream(channel)) {
                Objects.requireNonNull(is).transferTo(os);
            }catch (Exception e){
                Logger.error("Cannot save channel.yml file!");
                throw new RuntimeException(e);
            }
        }

        try(InputStream inputStream = new FileInputStream(channel)) {
            channelTree = new MapTree(new Yaml().load(inputStream));
        }catch (Exception e){
            Logger.error("Failed to load channel.yml: " + e.getMessage());
        }
    }

    public static Object getItem(String key) {
        return configTree.get(key);
    }

    public static MapTree getSection(String key){
        return configTree.getSection(key);
    }
    public static int getInt(String key){
        return configTree.getInt(key);
    }
    public static double getDouble(String key){
        return configTree.getDouble(key);
    }
    public static String getString(String key){
        return configTree.getString(key);
    }
    public static boolean getBoolean(String key){
        return configTree.getBoolean(key);
    }
    public static boolean contains(String key){
        return configTree.contains(key);
    }

    public static String getServerDisplayName(String serverName) {
        return configTree.getString("servers." + serverName, serverName);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void reload() {
        if(!ChatX.getDataDirectory().exists()) ChatX.getDataDirectory().mkdirs();

        File config = new File(ChatX.getDataDirectory(), "config.yml");
        if(!config.exists()){
            try (InputStream is = ChatX.class.getClassLoader().getResourceAsStream("config.yml"); OutputStream os = new FileOutputStream(config)) {
                Objects.requireNonNull(is).transferTo(os);
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        }

        try(InputStream is = new FileInputStream(config)){
            configTree = new MapTree(new Yaml().load(is));
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        File channel = new File(ChatX.getDataDirectory(), "channel.yml");
        if(!channel.exists()){
            try (InputStream is = ChatX.class.getClassLoader().getResourceAsStream("channel.yml"); OutputStream os = new FileOutputStream(channel)) {
                Objects.requireNonNull(is).transferTo(os);
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        }

        try(InputStream is = new FileInputStream(channel)){
            channelTree = new MapTree(new Yaml().load(is));
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }
}
