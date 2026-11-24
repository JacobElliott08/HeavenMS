/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.maps;

import java.awt.Point;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author Matze
 */
public class MapleFootholdTree {
    private MapleFootholdTree nw = null;
    private MapleFootholdTree ne = null;
    private MapleFootholdTree sw = null;
    private MapleFootholdTree se = null;
    private List<MapleFoothold> footholds = new LinkedList<MapleFoothold>();
    private Point p1;
    private Point p2;
    private Point center;
    private int depth = 0;
    private static int maxDepth = 8;
    private int maxDropX;
    private int minDropX;

    public MapleFootholdTree(Point p1, Point p2) {
        this.p1 = p1;
        this.p2 = p2;
        center = new Point((p2.x - p1.x) / 2, (p2.y - p1.y) / 2);
    }

    public MapleFoothold getById(int id) {
        for (MapleFoothold f : footholds) {
            if (f.getId() == id) return f;
        }
        if (nw == null) return null;
        MapleFoothold f = nw.getById(id);
        if (f == null) f = ne.getById(id);
        if (f == null) f = sw.getById(id);
        if (f == null) f = se.getById(id);
        return f;
    }

    public MapleFootholdTree(Point p1, Point p2, int depth) {
        this.p1 = p1;
        this.p2 = p2;
        this.depth = depth;
        center = new Point((p2.x - p1.x) / 2, (p2.y - p1.y) / 2);
    }

    public void insert(MapleFoothold f) {
        if (depth == 0) {
            if (f.getX1() > maxDropX) {
                maxDropX = f.getX1();
            }
            if (f.getX1() < minDropX) {
                minDropX = f.getX1();
            }
            if (f.getX2() > maxDropX) {
                maxDropX = f.getX2();
            }
            if (f.getX2() < minDropX) {
                minDropX = f.getX2();
            }
        }
        if (depth == maxDepth ||
                (f.getX1() >= p1.x && f.getX2() <= p2.x &&
                f.getY1() >= p1.y && f.getY2() <= p2.y)) {
            footholds.add(f);
        } else {
            if (nw == null) {
                nw = new MapleFootholdTree(p1, center, depth + 1);
                ne = new MapleFootholdTree(new Point(center.x, p1.y), new Point(p2.x, center.y), depth + 1);
                sw = new MapleFootholdTree(new Point(p1.x, center.y), new Point(center.x, p2.y), depth + 1);
                se = new MapleFootholdTree(center, p2, depth + 1);
            }
            if (f.getX2() <= center.x && f.getY2() <= center.y) {
                nw.insert(f);
            } else if (f.getX1() > center.x && f.getY2() <= center.y) {
                ne.insert(f);
            } else if (f.getX2() <= center.x && f.getY1() > center.y) {
                sw.insert(f);
            } else {
                se.insert(f);
            }
        }
    }

    private List<MapleFoothold> getRelevants(Point p) {
        return getRelevants(p, new LinkedList<MapleFoothold>());
    }

    private List<MapleFoothold> getRelevants(Point p, List<MapleFoothold> list) {
        list.addAll(footholds);
        if (nw != null) {
            if (p.x <= center.x && p.y <= center.y) {
                nw.getRelevants(p, list);
            } else if (p.x > center.x && p.y <= center.y) {
                ne.getRelevants(p, list);
            } else if (p.x <= center.x && p.y > center.y) {
                sw.getRelevants(p, list);
            } else {
                se.getRelevants(p, list);
            }
        }
        return list;
    }

    private MapleFoothold findWallR(Point p1, Point p2) {
        MapleFoothold ret;
        for (MapleFoothold f : footholds) {
            if (f.isWall() && f.getX1() >= p1.x && f.getX1() <= p2.x &&
                    f.getY1() >= p1.y && f.getY2() <= p1.y) {
                return f;
            }
        }
        if (nw != null) {
            if (p1.x <= center.x && p1.y <= center.y) {
                ret = nw.findWallR(p1, p2);
                if (ret != null) {
                    return ret;
                }
            }
            if ((p1.x > center.x || p2.x > center.x) && p1.y <= center.y) {
                ret = ne.findWallR(p1, p2);
                if (ret != null) {
                    return ret;
                }
            }
            if (p1.x <= center.x && p1.y > center.y) {
                ret = sw.findWallR(p1, p2);
                if (ret != null) {
                    return ret;
                }
            }
            if ((p1.x > center.x || p2.x > center.x) && p1.y > center.y) {
                ret = se.findWallR(p1, p2);
                if (ret != null) {
                    return ret;
                }
            }
        }
        return null;
    }

    public MapleFoothold findWall(Point p1, Point p2) {
        if (p1.y != p2.y) {
            throw new IllegalArgumentException();
        }
        return findWallR(p1, p2);
    }

    public MapleFoothold findBelow(Point p) {
        List<MapleFoothold> relevants = getRelevants(p);
        List<MapleFoothold> xMatches = new LinkedList<MapleFoothold>();
        for (MapleFoothold fh : relevants) {
            if (fh.getX1() <= p.x && fh.getX2() >= p.x) {
                xMatches.add(fh);
            }
        }
        Collections.sort(xMatches);
        for (MapleFoothold fh : xMatches) {
            if (!fh.isWall()) {
                if (fh.getY1() != fh.getY2()) {
                    int calcY;
                    double s1 = Math.abs(fh.getY2() - fh.getY1());
                    double s2 = Math.abs(fh.getX2() - fh.getX1());
                    double s4 = Math.abs(p.x - fh.getX1());
                    double alpha = Math.atan(s2 / s1);
                    double beta = Math.atan(s1 / s2);
                    double s5 = Math.cos(alpha) * (s4 / Math.cos(beta));
                    if (fh.getY2() < fh.getY1()) {
                        calcY = fh.getY1() - (int) s5;
                    } else {
                        calcY = fh.getY1() + (int) s5;
                    }
                    if (calcY >= p.y) {
                        return fh;
                    }
                } else {
                    if (fh.getY1() >= p.y) {
                        return fh;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Authority-attachment recovery only. Require agreement with the legacy
     * Point lookup and a unique nearby surface, except for two reciprocally
     * connected footholds meeting exactly at the feet. Their shared endpoint
     * is the same surface, so preserve the legacy lookup's side of the join.
     */
    public MapleFoothold findGroundForAttachment(Point p, double maxDistance) {
        List<MapleFoothold> matches = new LinkedList<>();
        collectGroundNear(p, maxDistance, matches);
        MapleFoothold preferred = findBelow(p);
        if (preferred == null || !matches.contains(preferred)) return null;
        if (matches.size() == 1) return preferred;
        if (matches.size() != 2) return null;
        MapleFoothold a = matches.get(0), b = matches.get(1);
        MapleFoothold left = a.getX2() == p.x && b.getX1() == p.x ? a
                : b.getX2() == p.x && a.getX1() == p.x ? b : null;
        if (left == null) return null;
        MapleFoothold right = left == a ? b : a;
        return left.getY2() == right.getY1()
                && left.getNext() == right.getId() && right.getPrev() == left.getId()
                ? preferred : null;
    }

    private void collectGroundNear(Point p, double maxDistance, List<MapleFoothold> matches) {
        for (MapleFoothold f : footholds) {
            if (f.isWall() || p.x < f.getX1() || p.x > f.getX2()) continue;
            double y = f.getY1() + (p.x - f.getX1()) * (f.getY2() - f.getY1())
                    / (double) (f.getX2() - f.getX1());
            if (y >= p.y && y - p.y <= maxDistance) matches.add(f);
        }
        if (nw != null) {
            nw.collectGroundNear(p, maxDistance, matches);
            ne.collectGroundNear(p, maxDistance, matches);
            sw.collectGroundNear(p, maxDistance, matches);
            se.collectGroundNear(p, maxDistance, matches);
        }
    }

    /** Exact nearest surface for fixed-step mob landing; no integer slope/feet rounding.
     * Leave the legacy Point query (drops, spawn placement, etc.) unchanged.
     */
    public MapleFoothold findBelow(double x, double y) {
        MapleFoothold best = null;
        double bestY = Double.POSITIVE_INFINITY;
        for (MapleFoothold f : footholds) {
            if (f.isWall() || (int) x < f.getX1() || (int) x > f.getX2()) continue;
            double ground = f.getY1() + (x - f.getX1()) * (f.getY2() - f.getY1())
                    / (double) (f.getX2() - f.getX1());
            if (ground >= y && ground < bestY) {
                best = f;
                bestY = ground;
            }
        }
        if (nw != null) {
            for (MapleFootholdTree child : new MapleFootholdTree[]{nw, ne, sw, se}) {
                MapleFoothold f = child.findBelow(x, y);
                if (f == null) continue;
                double ground = f.getY1() + (x - f.getX1()) * (f.getY2() - f.getY1())
                        / (double) (f.getX2() - f.getX1());
                if (ground < bestY) {
                    best = f;
                    bestY = ground;
                }
            }
        }
        return best;
    }

    public int getX1() {
        return p1.x;
    }

    public int getX2() {
        return p2.x;
    }

    public int getY1() {
        return p1.y;
    }

    public int getY2() {
        return p2.y;
    }

    public int getMaxDropX() {
        return maxDropX;
    }

    public int getMinDropX() {
        return minDropX;
    }
}
