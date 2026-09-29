package dev.xantha.vss.networking;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.ServerIdentityConfigurationTask;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class VSSNetworking {
    // 网络层协议通道名固定（不带版本号），确保新旧版本模组都能通过 NeoForge 的通道协商。
    // 应用层版本兼容性由 PROTOCOL_VERSION 和握手逻辑单独处理。
    private static final String PROTOCOL = "43";
    private static final String CLIENT_PACKET_HANDLERS_CLASS = "dev.xantha.vss.networking.client.VSSClientPacketHandlers";

    private VSSNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional() 使该网络通道在客户端缺失本模组时不参与通道协商，
        // 否则服务端会以 "客户端缺少此服务端需要的网络通道" 拒绝未安装 VSS 的客户端。
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional().executesOn(HandlerThread.MAIN);

        registrar.configurationToClient(
                ServerIdentityS2CPayload.TYPE,
                ServerIdentityS2CPayload.STREAM_CODEC,
                VSSNetworking::handleServerIdentity);

        registrar.playToServer(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.STREAM_CODEC, VSSServerNetworking::handleHandshake);
        registrar.playToServer(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBatchRequest);
        registrar.playToServer(CancelRequestC2SPayload.TYPE, CancelRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleCancel);
        registrar.playToServer(BandwidthUpdateC2SPayload.TYPE, BandwidthUpdateC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBandwidthUpdate);
        registrar.playToServer(RegionPresenceC2SPayload.TYPE, RegionPresenceC2SPayload.STREAM_CODEC, VSSServerNetworking::handleRegionPresence);
        registrar.playToServer(LostCityHintsC2SPayload.TYPE, LostCityHintsC2SPayload.STREAM_CODEC,
                VSSServerNetworking::handleLostCityHints);

        registrar.playToClient(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.STREAM_CODEC, VSSNetworking::handleSessionConfig);
        registrar.playToClient(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.STREAM_CODEC, VSSNetworking::handleBatchResponse);
        registrar.playToClient(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.STREAM_CODEC, VSSNetworking::handleDirtyColumns);
        registrar.playToClient(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.STREAM_CODEC, VSSNetworking::handleVoxelColumn);
        registrar.playToClient(FarPlayersS2CPayload.TYPE, FarPlayersS2CPayload.STREAM_CODEC, VSSNetworking::handleFarPlayers);
        registrar.playToClient(HandshakeRequestS2CPayload.TYPE, HandshakeRequestS2CPayload.STREAM_CODEC, VSSNetworking::handleHandshakeRequest);
        registrar.playToClient(WorldgenProfileS2CPayload.TYPE, WorldgenProfileS2CPayload.STREAM_CODEC, VSSNetworking::handleWorldgenProfile);
        registrar.playToClient(WorldgenProfileFragmentS2CPayload.TYPE, WorldgenProfileFragmentS2CPayload.STREAM_CODEC,
                VSSNetworking::handleWorldgenFragment);
        registrar.playToClient(LostCityHintsS2CPayload.TYPE, LostCityHintsS2CPayload.STREAM_CODEC,
                VSSNetworking::handleLostCityHints);
    }

    public static void registerConfigurationTasks(RegisterConfigurationTasksEvent event) {
        event.register(new ServerIdentityConfigurationTask(event.getListener()));
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (trySendToIntegratedHost(player, payload)) {
            return;
        }
        // 客户端未安装 VSS 时不具备该网络通道，跳过发送，
        // 否则服务端发送端会抛 "Payload ... may not be sent to the client!"。
        if (!player.connection.hasChannel(payload.type().id())) {
            return;
        }
        if (payload instanceof WorldgenProfileS2CPayload profile) {
            WorldgenProfileTransfer.send(profile, part -> PacketDistributor.sendToPlayer(player, part));
        } else {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static boolean trySendToIntegratedHost(ServerPlayer player, CustomPacketPayload payload) {
        if (!FMLEnvironment.dist.isClient()) {
            return false;
        }
        Object handled = invokeClientHandler(
                "tryHandleIntegratedHostPayload",
                new Class<?>[] {ServerPlayer.class, CustomPacketPayload.class},
                player,
                payload);
        return Boolean.TRUE.equals(handled);
    }

    private static void handleSessionConfig(SessionConfigS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleSessionConfig", new Class<?>[] {SessionConfigS2CPayload.class}, payload);
    }

    private static void handleBatchResponse(BatchResponseS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleBatchResponse", new Class<?>[] {BatchResponseS2CPayload.class}, payload);
    }

    private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleDirtyColumns", new Class<?>[] {DirtyColumnsS2CPayload.class}, payload);
    }

    private static void handleVoxelColumn(VoxelColumnS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleVoxelColumn", new Class<?>[] {VoxelColumnS2CPayload.class}, payload);
    }

    private static void handleFarPlayers(FarPlayersS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleFarPlayers", new Class<?>[] {FarPlayersS2CPayload.class}, payload);
    }

    private static void handleHandshakeRequest(HandshakeRequestS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleHandshakeRequest", new Class<?>[] {HandshakeRequestS2CPayload.class}, payload);
    }

    private static void handleWorldgenProfile(WorldgenProfileS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleWorldgenProfile", new Class<?>[] {WorldgenProfileS2CPayload.class}, payload);
    }

    private static void handleWorldgenFragment(WorldgenProfileFragmentS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleWorldgenFragment", new Class<?>[] {WorldgenProfileFragmentS2CPayload.class}, payload);
    }

    private static void handleLostCityHints(LostCityHintsS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleLostCityHints", new Class<?>[] {LostCityHintsS2CPayload.class}, payload);
    }

    private static void handleServerIdentity(ServerIdentityS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleServerIdentity", new Class<?>[] {ServerIdentityS2CPayload.class}, payload);
    }

    private static Object invokeClientHandler(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (!FMLEnvironment.dist.isClient()) {
            return null;
        }
        try {
            Class<?> handlersClass = Class.forName(CLIENT_PACKET_HANDLERS_CLASS);
            Method method = handlersClass.getMethod(methodName, parameterTypes);
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("VSS client packet handler failed: " + methodName, cause);
        } catch (ReflectiveOperationException e) {
            VSSLogger.error("Unable to invoke VSS client packet handler: " + methodName, e);
            return null;
        }
    }
}
