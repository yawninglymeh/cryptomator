package org.cryptomator.integrationapi;

import org.cryptomator.common.Environment;
import org.cryptomator.common.ShutdownHook;
import org.cryptomator.ui.fxapp.FxApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javafx.application.Platform;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@FxApplicationScoped
public class IntegrationApi implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(IntegrationApi.class);
	private static final long FX_DISPATCH_TIMEOUT_SECONDS = 5;

	private final Environment environment;
	private final CleartextPathMapper pathMapper;
	private final ShutdownHook shutdownHook;
	private IntegrationApiServer server;
	private ExecutorService requestExecutor;

	@Inject
	IntegrationApi(Environment environment, CleartextPathMapper pathMapper, ShutdownHook shutdownHook) {
		this.environment = environment;
		this.pathMapper = pathMapper;
		this.shutdownHook = shutdownHook;
	}

	public synchronized void start() {
		if (server != null) {
			return;
		}
		try {
			var config = IntegrationApiConfig.from(environment);
			if (config.isEmpty()) {
				return;
			}
			var enabledConfig = config.orElseThrow();
			requestExecutor = createRequestExecutor();
			server = new IntegrationApiServer(enabledConfig.port(), enabledConfig.token(), this::resolvePaths, requestExecutor);
			server.start();
			shutdownHook.runOnShutdown(ShutdownHook.PRIO_FIRST, this::close);
			LOG.info("Experimental integration API listening on 127.0.0.1:{}.", server.address().getPort());
		} catch (IllegalArgumentException | IOException e) {
			server = null;
			shutdownRequestExecutor();
			LOG.warn("Experimental integration API is disabled because its configuration is invalid or the loopback port is unavailable.");
		}
	}

	@Override
	public synchronized void close() {
		if (server != null) {
			server.close();
			server = null;
		}
		shutdownRequestExecutor();
	}

	private ExecutorService createRequestExecutor() {
		var threadNumber = new AtomicInteger(1);
		return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
			var thread = new Thread(runnable, "Integration API Worker " + threadNumber.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	private void shutdownRequestExecutor() {
		if (requestExecutor != null) {
			requestExecutor.shutdownNow();
			requestExecutor = null;
		}
	}

	private List<IntegrationApiServer.MappingResult> resolvePaths(List<String> paths) throws Exception {
		var preparedPaths = prepareOnFxThread(paths);
		return preparedPaths.stream().map(this::resolvePrepared).toList();
	}

	private List<PreparedPath> prepareOnFxThread(List<String> paths) throws Exception {
		if (Platform.isFxApplicationThread()) {
			return prepareNow(paths);
		}
		var task = new FutureTask<>(() -> prepareNow(paths));
		Platform.runLater(task);
		try {
			return task.get(FX_DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			task.cancel(false);
			throw e;
		}
	}

	private List<PreparedPath> prepareNow(List<String> paths) {
		return paths.stream().map(this::prepareOne).toList();
	}

	private PreparedPath prepareOne(String pathString) {
		if (pathString == null) {
			return PreparedPath.completed(IntegrationApiServer.MappingResult.error(PathMappingException.Code.INVALID_PATH.apiValue()));
		}
		try {
			return PreparedPath.pending(pathMapper.prepare(Path.of(pathString)));
		} catch (InvalidPathException e) {
			return PreparedPath.completed(IntegrationApiServer.MappingResult.error(PathMappingException.Code.INVALID_PATH.apiValue()));
		} catch (PathMappingException e) {
			return PreparedPath.completed(IntegrationApiServer.MappingResult.error(e.code().apiValue()));
		}
	}

	private IntegrationApiServer.MappingResult resolvePrepared(PreparedPath preparedPath) {
		if (preparedPath.completedResult() != null) {
			return preparedPath.completedResult();
		}
		try {
			return IntegrationApiServer.MappingResult.mapped(pathMapper.resolve(preparedPath.target()));
		} catch (IllegalStateException e) {
			return IntegrationApiServer.MappingResult.error("vault_state_changed");
		} catch (IOException | UnsupportedOperationException e) {
			return IntegrationApiServer.MappingResult.error("mapping_failed");
		}
	}

	private record PreparedPath(CleartextPathMapper.MappingTarget target, IntegrationApiServer.MappingResult completedResult) {

		private static PreparedPath pending(CleartextPathMapper.MappingTarget target) {
			return new PreparedPath(target, null);
		}

		private static PreparedPath completed(IntegrationApiServer.MappingResult result) {
			return new PreparedPath(null, result);
		}
	}
}
