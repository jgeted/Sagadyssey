package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** Explicit development launch only; always-hidden native rendering, no desktop input. */
@EventBusSubscriber(modid="sagadyssey",value=Dist.CLIENT)
public final class WarBackgroundClientGameTests {
    private static int checked;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("sagadyssey.war.background.client"))return;
        var mc=Minecraft.getInstance();var root=Path.of(System.getProperty("sagadyssey.war.background.root")).toAbsolutePath().normalize();
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(root))throw new IllegalStateException("isolated background directory required");
        long window=mc.getWindow().handle();
        if(!Boolean.getBoolean("sagadyssey.background.creationProtected")||!Boolean.getBoolean("sagadyssey.background.transformed")
                ||GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_VISIBLE)!=GLFW.GLFW_FALSE||GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_FOCUSED)!=GLFW.GLFW_FALSE
                ||GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_NORMAL)throw new IllegalStateException("background window invariant failed");
        checked++;
        if(checked%20==0){
            var proof=new LinkedHashMap<String,Object>();proof.put("process",ProcessHandle.current().pid());proof.put("windowHidden",true);proof.put("windowFocused",false);proof.put("cursorCaptured",false);proof.put("checks",checked);proof.put("screen",mc.screen==null?"none":mc.screen.getClass().getName());proof.put("realMinecraftRenderer",true);
            try{Files.writeString(root.resolve("background-window-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof));}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
}
