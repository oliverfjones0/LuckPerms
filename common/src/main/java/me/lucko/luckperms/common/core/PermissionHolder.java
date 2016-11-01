/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.core;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LocalizedNode;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.api.event.events.*;
import me.lucko.luckperms.common.LuckPermsPlugin;
import me.lucko.luckperms.common.api.internal.GroupLink;
import me.lucko.luckperms.common.api.internal.PermissionHolderLink;
import me.lucko.luckperms.common.groups.Group;
import me.lucko.luckperms.common.utils.Cache;
import me.lucko.luckperms.exceptions.ObjectAlreadyHasException;
import me.lucko.luckperms.exceptions.ObjectLacksException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Represents an object that can hold permissions
 * For example a User or a Group
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PermissionHolder {

    /**
     * The UUID of the user / name of the group.
     * Used to prevent circular inheritance issues
     */
    @Getter
    private final String objectName;

    /**
     * Reference to the main plugin instance
     */
    @Getter(AccessLevel.PROTECTED)
    private final LuckPermsPlugin plugin;

    private final Set<Node> nodes = new HashSet<>();
    private final Set<Node> transientNodes = new HashSet<>();

    private Cache<ImmutableSet<Node>> enduringCache = new Cache<>(() -> {
        synchronized (nodes) {
            return ImmutableSet.copyOf(nodes);
        }
    });

    private Cache<ImmutableSet<Node>> transientCache = new Cache<>(() -> {
        synchronized (transientNodes) {
            return ImmutableSet.copyOf(transientNodes);
        }
    });

    private Cache<ImmutableSortedSet<LocalizedNode>> cache = new Cache<>(() -> {
        TreeSet<LocalizedNode> combined = new TreeSet<>(PriorityComparator.reverse());
        Set<Node> enduring = getNodes();
        if (!enduring.isEmpty()) {
            combined.addAll(getNodes().stream()
                    .map(n -> makeLocal(n, getObjectName()))
                    .collect(Collectors.toList())
            );
        }
        Set<Node> tran = getTransientNodes();
        if (!tran.isEmpty()) {
            combined.addAll(getTransientNodes().stream()
                    .map(n -> makeLocal(n, getObjectName()))
                    .collect(Collectors.toList())
            );
        }

        Iterator<LocalizedNode> it = combined.iterator();
        Set<LocalizedNode> higherPriority = new HashSet<>();

        iterate:
        while (it.hasNext()) {
            LocalizedNode entry = it.next();
            for (LocalizedNode h : higherPriority) {
                if (entry.getNode().almostEquals(h.getNode())) {
                    it.remove();
                    continue iterate;
                }
            }
            higherPriority.add(entry);
        }
        return ImmutableSortedSet.copyOfSorted(combined);
    });

    private Cache<ImmutableSortedSet<LocalizedNode>> mergedCache = new Cache<>(() -> {
        TreeSet<LocalizedNode> combined = new TreeSet<>(PriorityComparator.reverse());
        Set<Node> enduring = getNodes();
        if (!enduring.isEmpty()) {
            combined.addAll(getNodes().stream()
                    .map(n -> makeLocal(n, getObjectName()))
                    .collect(Collectors.toList())
            );
        }
        Set<Node> tran = getTransientNodes();
        if (!tran.isEmpty()) {
            combined.addAll(getTransientNodes().stream()
                    .map(n -> makeLocal(n, getObjectName()))
                    .collect(Collectors.toList())
            );
        }

        Iterator<LocalizedNode> it = combined.iterator();
        Set<LocalizedNode> higherPriority = new HashSet<>();

        iterate:
        while (it.hasNext()) {
            LocalizedNode entry = it.next();
            for (LocalizedNode h : higherPriority) {
                if (entry.getNode().equalsIgnoringValueOrTemp(h.getNode())) {
                    it.remove();
                    continue iterate;
                }
            }
            higherPriority.add(entry);
        }
        return ImmutableSortedSet.copyOfSorted(combined);
    });

    @Getter
    private final Lock ioLock = new ReentrantLock();

    public abstract String getFriendlyName();

    public Set<Node> getNodes() {
        return enduringCache.get();
    }

    public Set<Node> getTransientNodes() {
        return transientCache.get();
    }

    private void invalidateCache(boolean enduring) {
        if (enduring) {
            enduringCache.invalidate();
        } else {
            transientCache.invalidate();
        }
        cache.invalidate();
        mergedCache.invalidate();
    }

    /**
     * Combines and returns this holders nodes in a priority order.
     * @return the holders transient and permanent nodes
     */
    public SortedSet<LocalizedNode> getPermissions(boolean mergeTemp) {
        return mergeTemp ? mergedCache.get() : cache.get();
    }

    /**
     * Removes temporary permissions that have expired
     * @return true if permissions had expired and were removed
     */
    public boolean auditTemporaryPermissions() {
        boolean work = false;

        synchronized (nodes) {
            boolean w = false;

            Iterator<Node> it = nodes.iterator();
            while (it.hasNext()) {
                Node entry = it.next();
                if (entry.hasExpired()) {
                    plugin.getApiProvider().fireEventAsync(new PermissionNodeExpireEvent(new PermissionHolderLink(this), entry));
                    w = true;
                    it.remove();
                }
            }

            if (w) {
                invalidateCache(true);
                work = true;
            }
        }

        synchronized (transientNodes) {
            boolean w = false;

            Iterator<Node> it = transientNodes.iterator();
            while (it.hasNext()) {
                Node entry = it.next();
                if (entry.hasExpired()) {
                    plugin.getApiProvider().fireEventAsync(new PermissionNodeExpireEvent(new PermissionHolderLink(this), entry));
                    w = true;
                    it.remove();
                }
            }

            if (w) {
                invalidateCache(false);
                work = true;
            }
        }

        return work;
    }

    /**
     * Resolves inherited nodes and returns them
     * @param excludedGroups a list of groups to exclude
     * @param context context to decide if groups should be applied
     * @return a set of nodes
     */
    public SortedSet<LocalizedNode> getAllNodes(List<String> excludedGroups, Contexts context) {
        SortedSet<LocalizedNode> all = new TreeSet<>((SortedSet<LocalizedNode>) getPermissions(true));

        if (excludedGroups == null) {
            excludedGroups = new ArrayList<>();
        }

        excludedGroups.add(getObjectName().toLowerCase());

        Set<Node> parents = all.stream()
                .map(LocalizedNode::getNode)
                .filter(Node::getValue)
                .filter(Node::isGroupNode)
                .collect(Collectors.toSet());

        MutableContextSet contexts = MutableContextSet.fromSet(context.getContexts());
        String server = contexts.getValues("server").stream().findAny().orElse(null);
        String world = contexts.getValues("world").stream().findAny().orElse(null);
        contexts.removeAll("server");
        contexts.removeAll("world");

        parents.removeIf(node ->
                !node.shouldApplyOnServer(server, context.isApplyGlobalGroups(), plugin.getConfiguration().isApplyingRegex()) ||
                !node.shouldApplyOnWorld(world, context.isApplyGlobalWorldGroups(), plugin.getConfiguration().isApplyingRegex()) ||
                !node.shouldApplyWithContext(contexts, false)
        );

        for (Node parent : parents) {
            Group group = plugin.getGroupManager().get(parent.getGroupName());
            if (group == null) {
                continue;
            }

            if (excludedGroups.contains(group.getObjectName().toLowerCase())) {
                continue;
            }

            inherited:
            for (LocalizedNode inherited : group.getAllNodes(excludedGroups, context)) {
                for (LocalizedNode existing : all) {
                    if (existing.getNode().almostEquals(inherited.getNode())) {
                        continue inherited;
                    }
                }

                all.add(inherited);
            }
        }

        return all;
    }

    /**
     * Gets all of the nodes that this holder has (and inherits), given the context
     * @param context the context for this request
     * @return a map of permissions
     */
    public Set<LocalizedNode> getAllNodesFiltered(Contexts context) {
        SortedSet<LocalizedNode> allNodes;

        if (context.isApplyGroups()) {
            allNodes = getAllNodes(null, context);
        } else {
            allNodes = new TreeSet<>((SortedSet<LocalizedNode>) getPermissions(true));
        }

        MutableContextSet contexts = MutableContextSet.fromSet(context.getContexts());
        String server = contexts.getValues("server").stream().findAny().orElse(null);
        String world = contexts.getValues("world").stream().findAny().orElse(null);
        contexts.removeAll("server");
        contexts.removeAll("world");

        allNodes.removeIf(node ->
                !node.shouldApplyOnServer(server, context.isIncludeGlobal(), plugin.getConfiguration().isApplyingRegex()) ||
                !node.shouldApplyOnWorld(world, context.isIncludeGlobalWorld(), plugin.getConfiguration().isApplyingRegex()) ||
                !node.shouldApplyWithContext(contexts, false)
        );

        Set<LocalizedNode> perms = ConcurrentHashMap.newKeySet();

        all:
        for (LocalizedNode ln : allNodes) {
            // Force higher priority nodes to override
            for (LocalizedNode alreadyIn : perms) {
                if (ln.getNode().getPermission().equals(alreadyIn.getNode().getPermission())) {
                    continue all;
                }
            }

            perms.add(ln);
        }

        return perms;
    }

    /**
     * Converts the output of {@link #getAllNodesFiltered(Contexts)}, and expands shorthand perms
     * @param context the context for this request
     * @return a map of permissions
     */
    public Map<String, Boolean> exportNodes(Contexts context, boolean lowerCase) {
        Map<String, Boolean> perms = new HashMap<>();

        for (LocalizedNode ln : getAllNodesFiltered(context)) {
            Node node = ln.getNode();

            perms.put(lowerCase ? node.getPermission().toLowerCase() : node.getPermission(), node.getValue());

            if (plugin.getConfiguration().isApplyingShorthand()) {
                List<String> sh = node.resolveShorthand();
                if (!sh.isEmpty()) {
                    sh.stream().map(s -> lowerCase ? s.toLowerCase() : s)
                            .filter(s -> !perms.containsKey(s))
                            .forEach(s -> perms.put(s, node.getValue()));
                }
            }
        }

        return ImmutableMap.copyOf(perms);
    }

    public void setNodes(Set<Node> set) {
        synchronized (nodes) {
            if (nodes.equals(set)) {
                return;
            }

            nodes.clear();
            nodes.addAll(set);
            invalidateCache(true);
        }
    }

    public void setTransientNodes(Set<Node> set) {
        synchronized (transientNodes) {
            if (transientNodes.equals(set)) {
                return;
            }

            transientNodes.clear();
            transientNodes.addAll(set);
            invalidateCache(false);
        }
    }

    @Deprecated
    public void setNodes(Map<String, Boolean> nodes) {
        synchronized (this.nodes) {
            this.nodes.clear();
            this.nodes.addAll(nodes.entrySet().stream()
                    .map(e -> makeNode(e.getKey(), e.getValue()))
                    .collect(Collectors.toList())
            );
            invalidateCache(true);
        }
    }

    public void addNodeUnchecked(Node node) {
        synchronized (nodes) {
            if (nodes.add(node)) {
                invalidateCache(true);
            }
        }
    }

    /**
     * Check if the holder has a permission node
     * @param node the node to check
     * @param t whether to check transient nodes
     * @return a tristate
     */
    public Tristate hasPermission(Node node, boolean t) {
        for (Node n : t ? getTransientNodes() : getNodes()) {
            if (n.almostEquals(node)) {
                return n.getTristate();
            }
        }

        return Tristate.UNDEFINED;
    }

    public Tristate hasPermission(Node node) {
        return hasPermission(node, false);
    }

    public boolean hasPermission(String node, boolean b) {
        return hasPermission(buildNode(node).setValue(b).build()).asBoolean() == b;
    }

    public boolean hasPermission(String node, boolean b, String server) {
        return hasPermission(buildNode(node).setValue(b).setServer(server).build()).asBoolean() == b;
    }

    public boolean hasPermission(String node, boolean b, String server, String world) {
        return hasPermission(buildNode(node).setValue(b).setServer(server).setWorld(world).build()).asBoolean() == b;
    }

    public boolean hasPermission(String node, boolean b, boolean temporary) {
        return hasPermission(buildNode(node).setValue(b).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    public boolean hasPermission(String node, boolean b, String server, boolean temporary) {
        return hasPermission(buildNode(node).setValue(b).setServer(server).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    public boolean hasPermission(String node, boolean b, String server, String world, boolean temporary) {
        return hasPermission(buildNode(node).setValue(b).setServer(server).setWorld(world).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    /**
     * Check if the holder inherits a node
     * @param node the node to check
     * @return the result of the lookup
     */
    public InheritanceInfo inheritsPermissionInfo(Node node) {
        for (LocalizedNode n : getAllNodes(null, Contexts.allowAll())) {
            if (n.getNode().almostEquals(node)) {
                return InheritanceInfo.of(n);
            }
        }

        return InheritanceInfo.empty();
    }

    /**
     * Check if the holder inherits a node
     * @param node the node to check
     * @return the Tristate result
     */
    public Tristate inheritsPermission(Node node) {
        return inheritsPermissionInfo(node).getResult();
    }

    public boolean inheritsPermission(String node, boolean b) {
        return inheritsPermission(buildNode(node).setValue(b).build()).asBoolean() == b;
    }

    public boolean inheritsPermission(String node, boolean b, String server) {
        return inheritsPermission(buildNode(node).setValue(b).setServer(server).build()).asBoolean() == b;
    }

    public boolean inheritsPermission(String node, boolean b, String server, String world) {
        return inheritsPermission(buildNode(node).setValue(b).setServer(server).setWorld(world).build()).asBoolean() == b;
    }

    public boolean inheritsPermission(String node, boolean b, boolean temporary) {
        return inheritsPermission(buildNode(node).setValue(b).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    public boolean inheritsPermission(String node, boolean b, String server, boolean temporary) {
        return inheritsPermission(buildNode(node).setValue(b).setServer(server).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    public boolean inheritsPermission(String node, boolean b, String server, String world, boolean temporary) {
        return inheritsPermission(buildNode(node).setValue(b).setServer(server).setWorld(world).setExpiry(temporary ? 10L : 0L).build()).asBoolean() == b;
    }

    /**
     * Sets a permission node
     * @param node the node to set
     * @throws ObjectAlreadyHasException if the holder has this permission already
     */
    public void setPermission(Node node) throws ObjectAlreadyHasException {
        if (hasPermission(node, false) != Tristate.UNDEFINED) {
            throw new ObjectAlreadyHasException();
        }

        synchronized (nodes) {
            nodes.add(node);
            invalidateCache(true);
        }

        plugin.getApiProvider().fireEventAsync(new PermissionNodeSetEvent(new PermissionHolderLink(this), node));
    }

    /**
     * Sets a transient permission node
     * @param node the node to set
     * @throws ObjectAlreadyHasException if the holder has this permission already
     */
    public void setTransientPermission(Node node) throws ObjectAlreadyHasException {
        if (hasPermission(node, true) != Tristate.UNDEFINED) {
            throw new ObjectAlreadyHasException();
        }

        synchronized (transientNodes) {
            transientNodes.add(node);
            invalidateCache(false);
        }

        plugin.getApiProvider().fireEventAsync(new PermissionNodeSetEvent(new PermissionHolderLink(this), node));
    }

    public void setPermission(String node, boolean value) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).build());
    }

    public void setPermission(String node, boolean value, String server) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).setServer(server).build());
    }

    public void setPermission(String node, boolean value, String server, String world) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).setServer(server).setWorld(world).build());
    }

    public void setPermission(String node, boolean value, long expireAt) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).setExpiry(expireAt).build());
    }

    public void setPermission(String node, boolean value, String server, long expireAt) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).setServer(server).setExpiry(expireAt).build());
    }

    public void setPermission(String node, boolean value, String server, String world, long expireAt) throws ObjectAlreadyHasException {
        setPermission(buildNode(node).setValue(value).setServer(server).setWorld(world).setExpiry(expireAt).build());
    }

    /**
     * Unsets a permission node
     * @param node the node to unset
     * @throws ObjectLacksException if the holder doesn't have this node already
     */
    public void unsetPermission(Node node) throws ObjectLacksException {
        if (hasPermission(node, false) == Tristate.UNDEFINED) {
            throw new ObjectLacksException();
        }

        synchronized (nodes) {
            nodes.removeIf(e -> e.almostEquals(node));
            invalidateCache(true);
        }

        if (node.isGroupNode()) {
            plugin.getApiProvider().fireEventAsync(new GroupRemoveEvent(new PermissionHolderLink(this),
                    node.getGroupName(), node.getServer().orElse(null), node.getWorld().orElse(null), node.isTemporary()));
        } else {
            plugin.getApiProvider().fireEventAsync(new PermissionNodeUnsetEvent(new PermissionHolderLink(this), node));
        }
    }

    /**
     * Unsets a transient permission node
     * @param node the node to unset
     * @throws ObjectLacksException if the holder doesn't have this node already
     */
    public void unsetTransientPermission(Node node) throws ObjectLacksException {
        if (hasPermission(node, true) == Tristate.UNDEFINED) {
            throw new ObjectLacksException();
        }

        synchronized (transientNodes) {
            transientNodes.removeIf(e -> e.almostEquals(node));
            invalidateCache(false);
        }

        if (node.isGroupNode()) {
            plugin.getApiProvider().fireEventAsync(new GroupRemoveEvent(new PermissionHolderLink(this),
                    node.getGroupName(), node.getServer().orElse(null), node.getWorld().orElse(null), node.isTemporary()));
        } else {
            plugin.getApiProvider().fireEventAsync(new PermissionNodeUnsetEvent(new PermissionHolderLink(this), node));
        }
    }

    public void unsetPermission(String node, boolean temporary) throws ObjectLacksException {
        unsetPermission(buildNode(node).setExpiry(temporary ? 10L : 0L).build());
    }

    public void unsetPermission(String node) throws ObjectLacksException {
        unsetPermission(buildNode(node).build());
    }

    public void unsetPermission(String node, String server) throws ObjectLacksException {
        unsetPermission(buildNode(node).setServer(server).build());
    }

    public void unsetPermission(String node, String server, String world) throws ObjectLacksException {
        unsetPermission(buildNode(node).setServer(server).setWorld(world).build());
    }

    public void unsetPermission(String node, String server, boolean temporary) throws ObjectLacksException {
        unsetPermission(buildNode(node).setServer(server).setExpiry(temporary ? 10L : 0L).build());
    }

    public void unsetPermission(String node, String server, String world, boolean temporary) throws ObjectLacksException {
        unsetPermission(buildNode(node).setServer(server).setWorld(world).setExpiry(temporary ? 10L : 0L).build());
    }

    public boolean inheritsGroup(Group group) {
        return group.getName().equalsIgnoreCase(this.getObjectName()) || hasPermission("group." + group.getName(), true);
    }

    public boolean inheritsGroup(Group group, String server) {
        return group.getName().equalsIgnoreCase(this.getObjectName()) || hasPermission("group." + group.getName(), true, server);
    }

    public boolean inheritsGroup(Group group, String server, String world) {
        return group.getName().equalsIgnoreCase(this.getObjectName()) || hasPermission("group." + group.getName(), true, server, world);
    }

    public void setInheritGroup(Group group) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), null, null, 0L));
    }

    public void setInheritGroup(Group group, String server) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true, server);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), server, null, 0L));
    }

    public void setInheritGroup(Group group, String server, String world) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true, server, world);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), server, world, 0L));
    }

    public void setInheritGroup(Group group, long expireAt) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true, expireAt);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), null, null, expireAt));
    }

    public void setInheritGroup(Group group, String server, long expireAt) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true, server, expireAt);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), server, null, expireAt));
    }

    public void setInheritGroup(Group group, String server, String world, long expireAt) throws ObjectAlreadyHasException {
        if (group.getName().equalsIgnoreCase(this.getObjectName())) {
            throw new ObjectAlreadyHasException();
        }

        setPermission("group." + group.getName(), true, server, world, expireAt);
        getPlugin().getApiProvider().fireEventAsync(new GroupAddEvent(new PermissionHolderLink(this), new GroupLink(group), server, world, expireAt));
    }

    public void unsetInheritGroup(Group group) throws ObjectLacksException {
        unsetPermission("group." + group.getName());
    }

    public void unsetInheritGroup(Group group, boolean temporary) throws ObjectLacksException {
        unsetPermission("group." + group.getName(), temporary);
    }

    public void unsetInheritGroup(Group group, String server) throws ObjectLacksException {
        unsetPermission("group." + group.getName(), server);
    }

    public void unsetInheritGroup(Group group, String server, String world) throws ObjectLacksException {
        unsetPermission("group." + group.getName(), server, world);
    }

    public void unsetInheritGroup(Group group, String server, boolean temporary) throws ObjectLacksException {
        unsetPermission("group." + group.getName(), server, temporary);
    }

    public void unsetInheritGroup(Group group, String server, String world, boolean temporary) throws ObjectLacksException {
        unsetPermission("group." + group.getName(), server, world, temporary);
    }

    /**
     * Clear all of the holders permission nodes
     */
    public void clearNodes() {
        synchronized (nodes) {
            nodes.clear();
            invalidateCache(true);
        }
    }

    public void clearNodes(String server) {
        String finalServer = Optional.ofNullable(server).orElse("global");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n -> n.getServer().orElse("global").equalsIgnoreCase(finalServer));
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearNodes(String server, String world) {
        String finalServer = Optional.ofNullable(server).orElse("global");
        String finalWorld = Optional.ofNullable(world).orElse("null");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                    n.getServer().orElse("global").equalsIgnoreCase(finalServer) &&
                            n.getWorld().orElse("null").equalsIgnoreCase(finalWorld));
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearParents() {
        synchronized (nodes) {
            boolean b = nodes.removeIf(Node::isGroupNode);
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearParents(String server) {
        String finalServer = Optional.ofNullable(server).orElse("global");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n -> n.isGroupNode() && n.getServer().orElse("global").equalsIgnoreCase(finalServer));
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearParents(String server, String world) {
        String finalServer = Optional.ofNullable(server).orElse("global");
        String finalWorld = Optional.ofNullable(world).orElse("null");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                            n.isGroupNode() &&
                            n.getServer().orElse("global").equalsIgnoreCase(finalServer) &&
                            n.getWorld().orElse("null").equalsIgnoreCase(finalWorld)
            );
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMeta() {
        synchronized (nodes) {
            boolean b = nodes.removeIf(n -> n.isMeta() || n.isPrefix() || n.isSuffix());
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMeta(String server) {
        String finalServer = Optional.ofNullable(server).orElse("global");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                    (n.isMeta() || n.isPrefix() || n.isSuffix()) &&
                            n.getServer().orElse("global").equalsIgnoreCase(finalServer)
            );
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMeta(String server, String world) {
        String finalServer = Optional.ofNullable(server).orElse("global");
        String finalWorld = Optional.ofNullable(world).orElse("null");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                    (n.isMeta() || n.isPrefix() || n.isSuffix()) && (
                            n.getServer().orElse("global").equalsIgnoreCase(finalServer) &&
                                    n.getWorld().orElse("null").equalsIgnoreCase(finalWorld)
                    )
            );
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMetaKeys(String key, boolean temp) {
        synchronized (nodes) {
            boolean b = nodes.removeIf(n -> n.isMeta() && (n.isTemporary() == temp) && n.getMeta().getKey().equalsIgnoreCase(key));
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMetaKeys(String key, String server, boolean temp) {
        String finalServer = Optional.ofNullable(server).orElse("global");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                    n.isMeta() && (n.isTemporary() == temp) &&
                            n.getMeta().getKey().equalsIgnoreCase(key) &&
                            n.getServer().orElse("global").equalsIgnoreCase(finalServer)
            );
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearMetaKeys(String key, String server, String world, boolean temp) {
        String finalServer = Optional.ofNullable(server).orElse("global");
        String finalWorld = Optional.ofNullable(world).orElse("null");

        synchronized (nodes) {
            boolean b = nodes.removeIf(n ->
                    n.isMeta() && (n.isTemporary() == temp) && n.getMeta().getKey().equalsIgnoreCase(key) &&
                            n.getServer().orElse("global").equalsIgnoreCase(finalServer) &&
                            n.getWorld().orElse("null").equalsIgnoreCase(finalWorld)
            );
            if (b) {
                invalidateCache(true);
            }
        }
    }

    public void clearTransientNodes() {
        synchronized (transientNodes) {
            transientNodes.clear();
            invalidateCache(false);
        }
    }

    /**
     * @return The temporary nodes held by the holder
     */
    public Set<Node> getTemporaryNodes() {
        return getPermissions(false).stream().filter(Node::isTemporary).collect(Collectors.toSet());
    }

    /**
     * @return The permanent nodes held by the holder
     */
    public Set<Node> getPermanentNodes() {
        return getPermissions(false).stream().filter(Node::isPermanent).collect(Collectors.toSet());
    }

    /**
     * Get a {@link List} of all of the groups the holder inherits, on all servers
     * @return a {@link List} of group names
     */
    public List<String> getGroupNames() {
        return getNodes().stream()
                .filter(Node::isGroupNode)
                .map(Node::getGroupName)
                .collect(Collectors.toList());
    }

    /**
     * Get a {@link List} of the groups the holder inherits on a specific server and world
     * @param server the server to check
     * @param world the world to check
     * @return a {@link List} of group names
     */
    public List<String> getLocalGroups(String server, String world) {
        return getNodes().stream()
                .filter(Node::isGroupNode)
                .filter(n -> n.shouldApplyOnWorld(world, false, true))
                .filter(n -> n.shouldApplyOnServer(server, false, true))
                .map(Node::getGroupName)
                .collect(Collectors.toList());
    }

    /**
     * Get a {@link List} of the groups the holder inherits on a specific server
     * @param server the server to check
     * @return a {@link List} of group names
     */
    public List<String> getLocalGroups(String server) {
        return getNodes().stream()
                .filter(Node::isGroupNode)
                .filter(n -> n.shouldApplyOnServer(server, false, true))
                .map(Node::getGroupName)
                .collect(Collectors.toList());
    }

    public static Map<String, Boolean> exportToLegacy(Set<Node> nodes) {
        Map<String, Boolean> m = new HashMap<>();
        for (Node node : nodes) {
            m.put(node.toSerializedNode(), node.getValue());
        }
        return m;
    }

    private static Node.Builder buildNode(String permission) {
        return new NodeBuilder(permission);
    }

    private static me.lucko.luckperms.common.utils.LocalizedNode makeLocal(Node node, String location) {
        return me.lucko.luckperms.common.utils.LocalizedNode.of(node, location);
    }

    private static Node makeNode(String s, Boolean b) {
        return NodeFactory.fromSerialisedNode(s, b);
    }
}
