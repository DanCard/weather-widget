# Regenerates shared/src/main/resources/basemap/ne50m_lines.bin for the Open-Meteo rain map.
# Source: Natural Earth 1:50m (public domain), GeoJSON from github.com/nvkelso/natural-earth-vector:
#   ne_50m_coastline, ne_50m_admin_0_boundary_lines_land, ne_50m_admin_1_states_provinces_lines, ne_50m_lakes
# Usage: python3 -I scripts/pack_natural_earth.py <dir with those .geojson> <out.bin> 0.02
# Packs Natural Earth line layers into a compact binary: int16 centi-degrees, Douglas-Peucker simplified.
import json, struct, sys
src, out, tol = sys.argv[1], sys.argv[2], float(sys.argv[3])
LAYERS = [(0,'ne_50m_coastline'),(1,'ne_50m_admin_0_boundary_lines_land'),(2,'ne_50m_admin_1_states_provinces_lines'),(3,'ne_50m_lakes')]
def dp(pts, tol):
    if len(pts) < 3: return pts
    keep=[False]*len(pts); keep[0]=keep[-1]=True; stack=[(0,len(pts)-1)]
    while stack:
        a,b=stack.pop(); (x1,y1),(x2,y2)=pts[a],pts[b]; dx,dy=x2-x1,y2-y1; L=(dx*dx+dy*dy)**.5 or 1e-12
        best,bi=-1,-1
        for i in range(a+1,b):
            x,y=pts[i]; d=abs(dy*x-dx*y+x2*y1-y2*x1)/L
            if d>best: best,bi=d,i
        if best>tol: keep[bi]=True; stack+= [(a,bi),(bi,b)]
    return [p for p,k in zip(pts,keep) if k]
lines=[]
for kind,name in LAYERS:
    g=json.load(open(f'{src}/{name}.geojson'))
    for f in g['features']:
        geom=f['geometry']; t=geom['type']; c=geom['coordinates']
        parts = {'LineString':[c],'MultiLineString':c,'Polygon':c[:1],'MultiPolygon':[p[0] for p in c]}[t]
        for p in parts:
            s=dp([(x,y) for x,y,*_ in p], tol)
            q=[]
            for x,y in s:
                pt=(round(y*100),round(x*100))
                if not q or q[-1]!=pt: q.append(pt)
            if len(q)>=2: lines.append((kind,q))
with open(out,'wb') as o:
    o.write(b'RLNE'); o.write(struct.pack('>BI',1,len(lines)))
    for kind,q in lines:
        o.write(struct.pack('>BH',kind,len(q)))
        for la,lo in q: o.write(struct.pack('>hh',la,lo))
print(len(lines), sum(len(q) for _,q in lines))
