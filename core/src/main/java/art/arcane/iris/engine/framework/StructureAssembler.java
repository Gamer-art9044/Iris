/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.framework;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.structure.JigsawPoolSelection;
import art.arcane.iris.engine.framework.structure.StructureGraphCatalog;
import art.arcane.iris.engine.framework.structure.StructureGraphCompilation;
import art.arcane.iris.engine.framework.structure.StructureGraphCompiler;
import art.arcane.iris.engine.framework.structure.StructureGraphDiagnostic;
import art.arcane.iris.engine.framework.structure.StructureGraphResolver;
import art.arcane.iris.engine.object.IrisDirection;
import art.arcane.iris.engine.object.IrisJigsawConnector;
import art.arcane.iris.engine.object.IrisJigsawPiece;
import art.arcane.iris.engine.object.IrisJigsawPieceEntry;
import art.arcane.iris.engine.object.IrisJigsawPool;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.JigsawJoint;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class StructureAssembler {
    private static final int HARD_PIECE_CAP = 512;
    private static final int MAX_DEPTH = 30;
    private static final int MAX_SIZE_CHUNKS = 32;
    private static final int[] NO_ROTATION = {0};
    private static final int[] Y_DEGREES = {0, 90, 180, 270};

    private final StructureGraphResolver resolver;
    private final IrisStructure structure;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int radius;

    private StructureAssembler(AssemblyOptions options) {
        this.resolver = options.resolver();
        this.structure = options.structure();
        this.originX = options.origin().getX();
        this.originY = options.origin().getY();
        this.originZ = options.origin().getZ();
        this.radius = this.structure.getMaxSizeChunks() >= 1
                && this.structure.getMaxSizeChunks() <= MAX_SIZE_CHUNKS
                ? this.structure.getMaxSizeChunks() * 16 : 0;
    }

    public static StructureAssembler forData(IrisData data, IrisStructure structure, IrisPosition origin) {
        IrisData activeData = Objects.requireNonNull(data, "Structure assembly data must not be null");
        IrisStructure activeStructure = Objects.requireNonNull(
                structure, "Structure assembly structure must not be null");
        return forCompilation(StructureGraphCatalog.compile(activeData, activeStructure), origin);
    }

    public static StructureAssembler forResolver(StructureGraphResolver resolver, IrisStructure structure,
                                                  IrisPosition origin) {
        StructureGraphResolver activeResolver = Objects.requireNonNull(
                resolver, "Structure assembly resolver must not be null");
        IrisStructure activeStructure = Objects.requireNonNull(
                structure, "Structure assembly structure must not be null");
        return forCompilation(StructureGraphCompiler.compile(activeStructure, activeResolver), origin);
    }

    public static StructureAssembler forCompilation(StructureGraphCompilation compilation, IrisPosition origin) {
        StructureGraphCompilation activeCompilation = Objects.requireNonNull(
                compilation, "Structure graph compilation must not be null");
        if (activeCompilation.hasErrors()) {
            throw invalidGraph(activeCompilation);
        }
        return new StructureAssembler(new AssemblyOptions(
                StructureGraphResolver.forCompiledGraph(activeCompilation.getGraph()),
                activeCompilation.getGraph().getStructure(), origin));
    }

    public KList<PlacedStructurePiece> assemble(RNG rng) {
        Objects.requireNonNull(rng, "Structure assembly RNG must not be null");
        if (radius == 0) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' has maxSizeChunks outside 1.." + MAX_SIZE_CHUNKS);
        }
        if (structure.getMaxDepth() < 1 || structure.getMaxDepth() > MAX_DEPTH) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' has maxDepth outside 1.." + MAX_DEPTH);
        }
        IrisJigsawPool startPool = resolver.loadPool(structure.getStartPool());
        if (startPool == null || startPool.getPieces() == null || startPool.getPieces().isEmpty()) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' has no resolvable start pool '" + structure.getStartPool() + "'");
        }

        KList<PlacedStructurePiece> placed = new KList<>();
        Deque<OpenConnector> open = new ArrayDeque<>();

        IrisJigsawPieceEntry startEntry = weightedPick(startPool, rng);
        if (startEntry == null) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' start pool has no positively weighted entry");
        }
        if (startEntry.isEmpty()) {
            return placed;
        }
        IrisJigsawPiece startPiece = resolver.loadPiece(startEntry.getPiece());
        if (startPiece == null) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' start pool references missing piece '" + startEntry.getPiece() + "'");
        }
        IrisObject startObject = resolver.loadObject(startPiece.getObject());
        if (startObject == null) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' start piece references missing object '" + startPiece.getObject() + "'");
        }
        if (startPiece.getConnectors() == null) {
            throw new IllegalStateException("Structure '" + structure.getLoadKey()
                    + "' start piece has no connector list");
        }

        int startRotY = startPiece.isRotatable() ? Y_DEGREES[rng.i(Y_DEGREES.length)] : 0;
        IrisObjectRotation startRot = IrisObjectRotation.of(0, startRotY, 0);
        PlacedStructurePiece start = build(startPiece, startObject, originX, originY, originZ, startRot);
        placed.add(start);
        enqueueConnectors(open, start, startObject, 0, null);

        while (!open.isEmpty() && placed.size() < HARD_PIECE_CAP) {
            OpenConnector c = open.poll();
            int depth = c.depth;
            if (depth > structure.getMaxDepth()) {
                continue;
            }

            IrisJigsawPool pool = resolver.loadPool(c.pool);
            if (pool == null) {
                throw new IllegalStateException("Structure '" + structure.getLoadKey()
                        + "' references missing connector pool '" + c.pool + "'");
            }

            if (!attachOne(open, placed, pool, c, depth, rng)) {
                return null;
            }
        }

        if (!open.isEmpty()) {
            throw assemblyFailure("exceeded the hard piece cap of " + HARD_PIECE_CAP
                    + " with " + open.size() + " connector(s) unresolved");
        }
        return placed;
    }

    private boolean attachOne(Deque<OpenConnector> open, KList<PlacedStructurePiece> placed,
                              IrisJigsawPool pool, OpenConnector c, int depth, RNG rng) {
        if (pool.getPieces() == null) {
            throw assemblyFailure("connector pool '" + c.pool + "' has no piece list");
        }
        if (pool.getPieces().isEmpty()) {
            return true;
        }
        if (depth < structure.getMaxDepth() && attachFromPool(open, placed, pool, c, depth, rng)) {
            return true;
        }

        String fallbackKey = JigsawPoolSelection.directFallbackKey(pool);
        if (fallbackKey.isEmpty()) {
            return depth >= structure.getMaxDepth();
        }
        IrisJigsawPool fallback = resolver.loadPool(fallbackKey);
        if (fallback == null) {
            throw assemblyFailure("references missing authored fallback pool '" + fallbackKey + "'");
        }
        if (fallback.getPieces() == null) {
            throw assemblyFailure("authored fallback pool '" + fallbackKey + "' has no piece list");
        }
        if (fallback.getPieces().isEmpty()) {
            return true;
        }
        if (attachFromPool(open, placed, fallback, c, depth, rng)) {
            return true;
        }
        return depth >= structure.getMaxDepth();
    }

    private boolean attachFromPool(Deque<OpenConnector> open, KList<PlacedStructurePiece> placed, IrisJigsawPool pool,
                                   OpenConnector c, int depth, RNG rng) {
        for (IrisJigsawPieceEntry entry : weightedOrder(pool, rng)) {
            if (entry.isEmpty()) {
                return true;
            }
            String pieceName = entry.getPiece();
            if (pieceName == null || pieceName.isBlank()) {
                throw assemblyFailure("pool contains a weighted entry without a piece key");
            }
            IrisJigsawPiece piece = resolver.loadPiece(pieceName);
            if (piece == null) {
                throw assemblyFailure("pool references missing piece '" + pieceName + "'");
            }
            if (piece.getObject() == null || piece.getObject().isBlank()) {
                throw assemblyFailure("piece '" + pieceName + "' has no object key");
            }
            IrisObject object = resolver.loadObject(piece.getObject());
            if (object == null) {
                throw assemblyFailure("piece '" + pieceName + "' references missing object '"
                        + piece.getObject() + "'");
            }
            if (piece.getConnectors() == null) {
                throw assemblyFailure("piece '" + pieceName + "' has no connector list");
            }

            for (IrisJigsawConnector cb : piece.getConnectors()) {
                requireConnector(cb, pieceName);
                if (c.targetName == null) {
                    throw assemblyFailure("open connector from pool '" + c.pool + "' has no target name");
                }
                if (!cb.getName().equals(c.targetName)) {
                    continue;
                }

                IrisDirection needed = c.facing.reverse();
                int[] rotations = piece.isRotatable() ? rotationCandidates(c.joint, rng) : NO_ROTATION;
                for (int yDeg : rotations) {
                    IrisObjectRotation rot = IrisObjectRotation.of(0, yDeg, 0);
                    IrisDirection rotatedFace = rot.rotate(cb.getDirection());
                    if (rotatedFace != needed) {
                        continue;
                    }
                    if (c.joint == JigsawJoint.ALIGNED && rot.rotate(cb.getTop()) != c.top) {
                        continue;
                    }

                    IrisPosition center = centerOf(object);
                    IrisPosition cr = new IrisPosition(
                            cb.getPosition().getX() - center.getX(),
                            cb.getPosition().getY() - center.getY(),
                            cb.getPosition().getZ() - center.getZ());
                    IrisPosition rcr = rot.rotate(cr, 0, 0, 0);
                    int wcx = c.wx + c.facing.x();
                    int wcy = c.wy + c.facing.y();
                    int wcz = c.wz + c.facing.z();
                    int px = wcx - rcr.getX();
                    int py = wcy - rcr.getY();
                    int pz = wcz - rcr.getZ();

                    PlacedStructurePiece candidate = build(piece, object, px, py, pz, rot);
                    if (!withinRadius(candidate) || collides(placed, candidate)) {
                        continue;
                    }

                    placed.add(candidate);
                    if (depth < structure.getMaxDepth()) {
                        enqueueConnectors(open, candidate, object, depth + 1, cb);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void enqueueConnectors(Deque<OpenConnector> open, PlacedStructurePiece p, IrisObject object, int depth, IrisJigsawConnector skip) {
        if (p.getPiece().getConnectors() == null) {
            throw assemblyFailure("placed piece for object '" + object.getLoadKey() + "' has no connector list");
        }
        IrisPosition center = centerOf(object);
        for (IrisJigsawConnector con : p.getPiece().getConnectors()) {
            if (con == skip) {
                continue;
            }
            requireConnector(con, object.getLoadKey());
            if (con.getPool() == null || con.getPool().isBlank()) {
                continue;
            }
            IrisPosition cr = new IrisPosition(
                    con.getPosition().getX() - center.getX(),
                    con.getPosition().getY() - center.getY(),
                    con.getPosition().getZ() - center.getZ());
            IrisPosition rcr = p.getRotation().rotate(cr, 0, 0, 0);
            IrisDirection facing = p.getRotation().rotate(con.getDirection());
            IrisDirection top = p.getRotation().rotate(con.getTop());
            open.add(new OpenConnector(
                    p.getX() + rcr.getX(),
                    p.getY() + rcr.getY(),
                    p.getZ() + rcr.getZ(),
                    facing, top, con.getPool(), con.getName(), con.getTargetName(), con.getJoint(), depth));
        }
    }

    private PlacedStructurePiece build(IrisJigsawPiece piece, IrisObject object, int x, int y, int z, IrisObjectRotation rot) {
        int width = Math.max(1, object.getW());
        int height = Math.max(1, object.getH());
        int depth = Math.max(1, object.getD());
        int localMinX = -(width / 2);
        int localMinY = -(height / 2);
        int localMinZ = -(depth / 2);
        int localMaxX = localMinX + width - 1;
        int localMaxY = localMinY + height - 1;
        int localMaxZ = localMinZ + depth - 1;
        IrisPosition rotatedMin = rot.rotate(new IrisPosition(localMinX, localMinY, localMinZ), 0, 0, 0);
        IrisPosition rotatedMax = rot.rotate(new IrisPosition(localMaxX, localMaxY, localMaxZ), 0, 0, 0);
        int minX = Math.min(rotatedMin.getX(), rotatedMax.getX());
        int minY = Math.min(rotatedMin.getY(), rotatedMax.getY());
        int minZ = Math.min(rotatedMin.getZ(), rotatedMax.getZ());
        int maxX = Math.max(rotatedMin.getX(), rotatedMax.getX());
        int maxY = Math.max(rotatedMin.getY(), rotatedMax.getY());
        int maxZ = Math.max(rotatedMin.getZ(), rotatedMax.getZ());
        return new PlacedStructurePiece(piece, object, x, y, z, rot,
                x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
    }

    private boolean withinRadius(PlacedStructurePiece p) {
        return p.getMinX() >= originX - radius && p.getMaxX() <= originX + radius
                && p.getMinZ() >= originZ - radius && p.getMaxZ() <= originZ + radius;
    }

    private boolean collides(KList<PlacedStructurePiece> placed, PlacedStructurePiece candidate) {
        for (PlacedStructurePiece p : placed) {
            if (p.intersects(candidate)) {
                return true;
            }
        }
        return false;
    }

    private int[] rotationCandidates(JigsawJoint joint, RNG rng) {
        if (joint == JigsawJoint.ALIGNED) {
            return Y_DEGREES;
        }
        int[] shuffled = {0, 90, 180, 270};
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = rng.i(i + 1);
            int t = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = t;
        }
        return shuffled;
    }

    private IrisPosition centerOf(IrisObject object) {
        return new IrisPosition(object.getW() / 2, object.getH() / 2, object.getD() / 2);
    }

    private IrisJigsawPieceEntry weightedPick(IrisJigsawPool pool, RNG rng) {
        if (pool.getPieces() == null) {
            throw assemblyFailure("pool has no piece list");
        }
        long total = 0L;
        for (IrisJigsawPieceEntry e : pool.getPieces()) {
            if (e == null || e.getWeight() <= 0) {
                throw assemblyFailure("pool contains a null or non-positive weighted entry");
            }
            total += e.getWeight();
        }
        if (total <= 0) {
            return null;
        }
        long t = rng.nextLong(total);
        for (IrisJigsawPieceEntry e : pool.getPieces()) {
            t -= e.getWeight();
            if (t < 0) {
                return e;
            }
        }
        return null;
    }

    private KList<IrisJigsawPieceEntry> weightedOrder(IrisJigsawPool pool, RNG rng) {
        KList<IrisJigsawPieceEntry> remaining = new KList<>();
        if (pool.getPieces() != null) {
            for (IrisJigsawPieceEntry entry : pool.getPieces()) {
                if (entry == null || entry.getWeight() <= 0) {
                    throw assemblyFailure("pool contains a null or non-positive weighted entry");
                }
                remaining.add(entry);
            }
        }
        KList<IrisJigsawPieceEntry> order = new KList<>();
        while (!remaining.isEmpty()) {
            long total = 0L;
            for (IrisJigsawPieceEntry e : remaining) {
                total += e.getWeight();
            }
            long t = rng.nextLong(total);
            int idx = 0;
            for (int i = 0; i < remaining.size(); i++) {
                t -= remaining.get(i).getWeight();
                if (t < 0) {
                    idx = i;
                    break;
                }
            }
            order.add(remaining.remove(idx));
        }
        return order;
    }

    private void requireConnector(IrisJigsawConnector connector, String pieceKey) {
        if (connector == null || connector.getPosition() == null || connector.getDirection() == null
                || connector.getTop() == null || connector.getName() == null
                || connector.getTargetName() == null || connector.getJoint() == null) {
            throw assemblyFailure("piece '" + pieceKey + "' contains a malformed connector");
        }
    }

    private IllegalStateException assemblyFailure(String detail) {
        return new IllegalStateException("Structure '" + structure.getLoadKey() + "' assembly failed: " + detail);
    }

    private static IllegalStateException invalidGraph(StructureGraphCompilation compilation) {
        String structureKey = compilation.getGraph().getStructureKey();
        StringBuilder detail = new StringBuilder();
        for (StructureGraphDiagnostic diagnostic : compilation.getDiagnostics()) {
            if (diagnostic.severity() != StructureGraphDiagnostic.Severity.ERROR) {
                continue;
            }
            if (!detail.isEmpty()) {
                detail.append("; ");
            }
            detail.append(diagnostic.message());
        }
        return new IllegalStateException("Structure '" + structureKey
                + "' assembly rejected its invalid graph: " + detail);
    }

    private record OpenConnector(int wx, int wy, int wz, IrisDirection facing, IrisDirection top,
                                 String pool, String name,
                                 String targetName, JigsawJoint joint, int depth) {
    }

    private record AssemblyOptions(StructureGraphResolver resolver, IrisStructure structure,
                                   IrisPosition origin) {
        private AssemblyOptions {
            Objects.requireNonNull(resolver, "Structure assembly resolver must not be null");
            Objects.requireNonNull(structure, "Structure assembly structure must not be null");
            Objects.requireNonNull(origin, "Structure assembly origin must not be null");
        }
    }
}
