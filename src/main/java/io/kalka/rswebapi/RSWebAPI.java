package io.kalka.rswebapi;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("rswebapi")
public class RSWebAPI {

    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private ServerLevel overworld;

    private HttpServer server;

    public RSWebAPI() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rswebapi").then(Commands.literal("reload").executes(context -> {
            if (server == null) {
                LOGGER.error("The server is not started!");
                return 0;
            }
            LOGGER.info("Restarting server");
            server.stop(0);
            try {
                startServer();
                LOGGER.info("Started!");
            } catch (IOException e) {
                LOGGER.error("Failed to start server", e);
            }
            return 1;
        })));
    }

    private void handleContext(HttpExchange ctx) throws IOException {
        // get server world
        if (overworld == null) {
            LOGGER.error("Could not get world :(");
            ctx.sendResponseHeaders(500, 0);
            ctx.close();
            return;
        }

        Collection<INetwork> networks = API.instance().getNetworkManager(overworld).all();

        Map<String, Map<String, Integer>> networksMap = new HashMap<>();

        for (INetwork network : networks) {
            Collection<StackListEntry<ItemStack>> list = network.getItemStorageCache().getList().getStacks();

            Map<String, Integer> stacks = new HashMap<>();

            for (StackListEntry<ItemStack> entry : list) {
                ItemStack stack = entry.getStack();
                if (stack.getTag() == null) {
                    break;
                }
                Tag name = stack.getTag().get("display");
                if (name == null) {
                    break;
                }
                if (name.getAsString().contains("rsweb")) {
                    String id = name.getAsString().replace("{Name:'{\"text\":\"rsweb", "").replace("\"}'}", "");
                    if (id.isEmpty() || id.equals("-") || id.isBlank()) {
                        LOGGER.error("We found a tag, but without a valid id. Make sure you follow the format of naming the item {rsweb-<id>} e.g {rsweb-<name>}");
                        break;
                    }
                    String filteredId = id.replace("-", "");
                    if (networksMap.containsKey(filteredId)) {
                        LOGGER.error("We found a tag, but with a duplicate id ({}) at the network in position {}. Please make sure you don't have any conflicting names on the networks within your server!", filteredId, network.getPosition());
                        break;
                    }
                    LOGGER.info("Found rsweb-{}", filteredId);
                    for (StackListEntry<ItemStack> items : list) {
                        stacks.put(Objects.requireNonNull(items.getStack().getItem().getRegistryName()).toString(), items.getStack().getCount());
                    }
                    networksMap.put(filteredId, stacks);
                    break;
                }
            }
        }

        String json = new Gson().toJson(networksMap);
        ctx.getResponseHeaders().set("Content-Type", "application/json");
        ctx.sendResponseHeaders(200, json.length());
        ctx.getResponseBody().write(json.getBytes());


        ctx.close();
    }

    private HttpServer createServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8989), 0);
        HttpContext context = server.createContext("/");
        context.setHandler(this::handleContext);
        server.setExecutor(null); // creates a default executor
        return server;
    }

    private void startServer() throws IOException {
        // A new HTTP server instance is created because we cannot reuse the same server instance incase of a restart
        server = createServer();
        server.start();
    }

    public void onServerStarted(final ServerStartedEvent event) {
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        overworld = event.getServer().overworld();
        LOGGER.info("HELLO from server starting");
        try {
            LOGGER.info("Starting RSWebAPI http server...");
            startServer();
        } catch (IOException e) {
            LOGGER.info("RSWebAPI failed to start because we could not create the HTTP server");
            e.printStackTrace();
        }
    }
}
