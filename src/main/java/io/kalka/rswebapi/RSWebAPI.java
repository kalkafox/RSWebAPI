package io.kalka.rswebapi;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;


// The value here should match an entry in the META-INF/mods.toml file
@Mod("rswebapi")
public class RSWebAPI {

    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private MinecraftServer server;

    private HttpServer httpServer;

    private static long startTime;

    public RSWebAPI() {
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(this::onServerStarted);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rswebapi").then(Commands.literal("reload").executes(context -> {
            if (httpServer == null) {
                LOGGER.error("The server is not started!");
                return 0;
            }
            LOGGER.info("Restarting server");
            httpServer.stop(0);
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
        if (server == null) {
            LOGGER.error("Could not get server :(");
            ctx.sendResponseHeaders(500, 0);
            ctx.close();
            return;
        }

        Collection<INetwork> networks = API.instance().getNetworkManager(server.overworld()).all();

        Map<String, Object> context = new HashMap<>();

        Map<String, Collection<Map<String, Object>>> networksMap = new HashMap<>();

        long sum = 0L;
        for (long l : server.tickTimes) {
            sum += l;
        }
        double mean = (sum / (double) server.tickTimes.length) * 1.0E-006D;
        int tps = (int) Math.min(1000.0D / mean, 20);

        for (INetwork network : networks) {
            Collection<StackListEntry<ItemStack>> list = network.getItemStorageCache().getList().getStacks();

            Collection<Map<String, Object>> stacks = new ArrayList<>();

            for (StackListEntry<ItemStack> entry : list) {
                ItemStack stack = entry.getStack();
                if (stack.getTag() == null) {
                    continue;
                }
                Tag name = stack.getTag().get("display");
                if (name == null) {
                    continue;
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
                        Map<String, Object> item = new HashMap<>();
                        List<String> itemTags = new ArrayList<>();

                        items.getStack().getTags().toList().forEach(tag -> {
                            itemTags.add(tag.location().toString());
                        });


                        LOGGER.info("tags: {}", itemTags);
                        item.put("id", Objects.requireNonNull(items.getStack().getItem().getRegistryName()).toString());
                        item.put("count", items.getStack().getCount());
                        item.put("tags", itemTags);
                        stacks.add(item);
                    }
                    LOGGER.info(String.valueOf(stacks));
                    networksMap.put(filteredId, stacks);
                    break;
                }
            }
        }

        context.put("networks", networksMap);
        context.put("tps", tps);
        context.put("uptime", System.currentTimeMillis() - startTime);
        context.put("mean", mean);

        String json = new Gson().toJson(context);
        ctx.getResponseHeaders().set("Content-Type", "application/json");
        ctx.sendResponseHeaders(200, json.length());
        ctx.getResponseBody().write(json.getBytes());


        ctx.close();
    }

    private HttpServer createServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(8989), 0);
        HttpContext context = httpServer.createContext("/");
        context.setHandler(this::handleContext);
        httpServer.setExecutor(null); // creates a default executor
        return httpServer;
    }

    private void startServer() throws IOException {
        // A new HTTP server instance is created because we cannot reuse the same server instance incase of a restart
        httpServer = createServer();
        httpServer.start();
    }

    public void onServerStarted(final ServerStartedEvent event) {
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        server = event.getServer();
        startTime = System.currentTimeMillis();
        try {
            LOGGER.info("Starting RSWebAPI http server...");
            startServer();
        } catch (IOException e) {
            LOGGER.info("RSWebAPI failed to start because we could not create the HTTP server");
            e.printStackTrace();
        }
    }
}
