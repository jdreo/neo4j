/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.locking;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * State control class for Locks.Clients.
 * Client state represent current Locks.Client state: <b>ACTIVE/PREPARE/STOPPED</b> and number of active clients.
 * <p/>
 * Client states are:
 * <ul>
 * <li><b>ACTIVE</b> state of fully functional locks client without any restriction or operations limitations.</li>
 * <li><b>PREPARE</b> state prevents transition into STOPPED state, unless forced as part of closing the lock
 * client.</li>
 * <li><b>STOPPED</b> all current lock acquisitions will be interrupted/terminated without obtaining corresponding
 * lock, new acquisitions will not be possible anymore, all locks that client holds are preserved.</li>
 * </ul>
 */
public final class LockClientStateHolder {
    private static final int FLAG_BITS = 2;
    private static final int CLIENT_BITS = Integer.SIZE - FLAG_BITS;
    private static final int STOPPED = 1 << CLIENT_BITS;
    private static final int PREPARE = 1 << CLIENT_BITS - 1;
    private static final int STATE_BIT_MASK = STOPPED | PREPARE;
    private static final int INITIAL_STATE = 0;
    private final AtomicInteger clientState = new AtomicInteger(INITIAL_STATE);

    /**
     * Check if we still have any active client
     *
     * @return true if have any open client, false otherwise.
     */
    public boolean hasActiveClients() {
        return getActiveClients(clientState.get()) > 0;
    }

    /**
     * Check if we still have one active client
     *
     * @return true if have one open client, false otherwise.
     */
    public boolean isSingleClient() {
        return getActiveClients(clientState.get()) == 1;
    }

    /**
     * Move the client to the PREPARE state, unless it is already STOPPED.
     */
    public void prepare(LockManager.Client client) {
        int currentValue;
        int newValue;
        do {
            currentValue = clientState.get();
            if (isStopped(currentValue)) {
                throw new LockClientStoppedException(client);
            }
            newValue = stateWithNewStatus(currentValue, PREPARE);
        } while (!clientState.compareAndSet(currentValue, newValue));
    }

    /**
     * Move the client to STOPPED, unless it is already in PREPARE or STOPPED
     */
    public boolean stopClient() {
        int currentValue;
        int newValue;
        do {
            currentValue = clientState.get();
            if (isPrepare(currentValue)) {
                return false; // Can't stop clients that are in PREPARE
            }
            if (isStopped(currentValue)) {
                return false;
            }
            newValue = stateWithNewStatus(currentValue, STOPPED);
        } while (!clientState.compareAndSet(currentValue, newValue));
        return true;
    }

    /**
     * Move the client to STOPPED as part of closing the current client, regardless of what state it is currently in.
     */
    public void closeClient() {
        int currentValue;
        int newValue;
        do {
            currentValue = clientState.get();
            newValue = stateWithNewStatus(currentValue, STOPPED);
        } while (!clientState.compareAndSet(currentValue, newValue));
    }

    /**
     * Increment active number of clients that use current state instance.
     *
     * @param client the locks client associated with this state; used only to create pretty exception
     * with {@link LockClientStoppedException#LockClientStoppedException(LockManager.Client)}.
     * @throws LockClientStoppedException when stopped.
     */
    public void incrementActiveClients(LockManager.Client client) {
        int currentState;
        do {
            currentState = clientState.get();
            if (isStopped(currentState)) {
                throw new LockClientStoppedException(client);
            }
        } while (!clientState.compareAndSet(currentState, incrementActiveClients(currentState)));
    }

    /**
     * Decrement number of active clients that use current client state object.
     */
    public void decrementActiveClients() {
        int currentState;
        do {
            currentState = clientState.get();
        } while (!clientState.compareAndSet(currentState, decrementActiveClients(currentState)));
    }

    /**
     * Check if stopped
     *
     * @return true if client is stopped, false otherwise
     */
    public boolean isStopped() {
        return isStopped(clientState.get());
    }

    /**
     * Check if prepared
     *
     * @return true if client is prepared, false otherwise
     */
    public boolean isPrepared() {
        return isPrepare(clientState.get());
    }

    /**
     * Reset state to initial state disregard any current state or number of active clients
     */
    public void reset() {
        clientState.set(INITIAL_STATE);
    }

    private static boolean isPrepare(int clientState) {
        return getStatus(clientState) == PREPARE;
    }

    private static boolean isStopped(int clientState) {
        return getStatus(clientState) == STOPPED;
    }

    private static int getStatus(int clientState) {
        return clientState & STATE_BIT_MASK;
    }

    private static int getActiveClients(int clientState) {
        return clientState & ~STATE_BIT_MASK;
    }

    private static int stateWithNewStatus(int clientState, int newStatus) {
        return newStatus | getActiveClients(clientState);
    }

    private static int incrementActiveClients(int clientState) {
        return getStatus(clientState) | Math.incrementExact(getActiveClients(clientState));
    }

    private static int decrementActiveClients(int clientState) {
        return getStatus(clientState) | Math.decrementExact(getActiveClients(clientState));
    }
}
