package it.tdlight.common;

import it.tdlight.jni.TdApi.Error;
import it.tdlight.jni.TdApi.Function;
import it.tdlight.jni.TdApi.Object;
import it.tdlight.jni.TdApi.Update;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class InternalClient implements ClientEventsHandler, TelegramClient {

	static final ReentrantReadWriteLock clientInitializationLock = new ReentrantReadWriteLock(true);
	private final ConcurrentHashMap<Long, Handler> handlers = new ConcurrentHashMap<Long, Handler>();

	private final int clientId;
	private final InternalClientManager clientManager;
	private final Handler updateHandler;
	private final MultiHandler updatesHandler;
	private final ExceptionHandler defaultExceptionHandler;

	private volatile boolean isClosed;

	public InternalClient(InternalClientManager clientManager,
			ResultHandler updateHandler,
			ExceptionHandler updateExceptionHandler,
			ExceptionHandler defaultExceptionHandler) {
		clientInitializationLock.writeLock().lock();
		try {
			this.updateHandler = new Handler(updateHandler, updateExceptionHandler);
			this.updatesHandler = null;
			this.defaultExceptionHandler = defaultExceptionHandler;
			this.clientManager = clientManager;
			this.clientId = NativeClientAccess.create();

			clientManager.registerClient(clientId, this);
		} finally {
			clientInitializationLock.writeLock().unlock();
		}
	}

	public InternalClient(InternalClientManager clientManager,
			UpdatesHandler updatesHandler,
			ExceptionHandler updateExceptionHandler,
			ExceptionHandler defaultExceptionHandler) {
		clientInitializationLock.writeLock().lock();
		try {
			this.updateHandler = null;
			this.updatesHandler = new MultiHandler(updatesHandler, updateExceptionHandler);
			this.clientManager = clientManager;
			this.defaultExceptionHandler = defaultExceptionHandler;
			this.clientId = NativeClientAccess.create();

			clientManager.registerClient(clientId, this);
		} finally {
			clientInitializationLock.writeLock().unlock();
		}
	}

	@Override
	public int getClientId() {
		return clientId;
	}

	@Override
	public void handleEvents(boolean isClosed, long[] eventIds, Object[] events) {
		if (updatesHandler != null) {
			LongArrayList idsToFilter = new LongArrayList(eventIds);
			ObjectArrayList<Object> eventsToFilter = new ObjectArrayList<>(events);

			for (int i = eventIds.length - 1; i >= 0; i--) {
				if (eventIds[i] != 0) {
					idsToFilter.removeLong(i);
					eventsToFilter.remove(i);

					long eventId = eventIds[i];
					Object event = events[i];

					Handler handler = handlers.remove(eventId);
					handleResponse(eventId, event, handler);
				}
			}

			eventsToFilter.removeIf(event -> {
				if (event instanceof Error) {
					handleException(updatesHandler.getExceptionHandler(), new TDLibException((Error) event));
					return true;
				}
				return false;
			});

			ObjectArrayList<Update> updates = new ObjectArrayList<>(eventsToFilter.size());
			for (Object object : eventsToFilter) {
				updates.add((Update) object);
			}

			try {
				updatesHandler.getUpdatesHandler().onUpdates(updates);
			} catch (Throwable cause) {
				handleException(updatesHandler.getExceptionHandler(), cause);
			}
		} else {
			for (int i = 0; i < eventIds.length; i++) {
				handleEvent(eventIds[i], events[i]);
			}
		}

		if (isClosed) {
			this.isClosed = true;
		}
	}

	/**
	 * Handles only a response (not an update!)
	 */
	private void handleResponse(long eventId, Object event, Handler handler) {
		if (handler != null) {
			try {
				if (event instanceof Error) {
					handleException(handler.getExceptionHandler(), new TDLibException((Error) event));
				} else {
					handler.getResultHandler().onResult(event);
				}
			} catch (Throwable cause) {
				handleException(handler.getExceptionHandler(), cause);
			}
		} else {
			System.err.println("Unknown event id " + eventId + ", the event has been dropped!");
		}
	}

	/**
	 * Handles a response or an update
	 */
	private void handleEvent(long eventId, Object event) {
		if (updatesHandler != null || updateHandler == null) throw new IllegalStateException();
		Handler handler = eventId == 0 ? updateHandler : handlers.remove(eventId);
		handleResponse(eventId, event, handler);
	}

	private void handleException(ExceptionHandler exceptionHandler, Throwable cause) {
		if (exceptionHandler == null) {
			exceptionHandler = defaultExceptionHandler;
		}
		if (exceptionHandler != null) {
			try {
				exceptionHandler.onException(cause);
			} catch (Throwable ignored) {}
		}
	}

	@Override
	public void send(Function query, ResultHandler resultHandler, ExceptionHandler exceptionHandler) {
		ensureOpen();
		long queryId = clientManager.getNextQueryId();
		if (resultHandler != null) {
			handlers.put(queryId, new Handler(resultHandler, exceptionHandler));
		}
		NativeClientAccess.send(clientId, queryId, query);
	}

	@Override
	public Object execute(Function query) {
		ensureOpen();
		return NativeClientAccess.execute(query);
	}

	private void ensureOpen() {
		if (isClosed) {
			throw new IllegalStateException("The client is closed!");
		}
	}
}
