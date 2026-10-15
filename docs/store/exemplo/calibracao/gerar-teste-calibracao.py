import math
def fwd(lat, lon, zone=23):
    f=1/298.257222101; a=6378137.0; n=f/(2-f)
    aa=a/(1+n)*(1+n*n/4+n**4/64)
    al=[n/2-2*n*n/3+5*n**3/16, 13*n*n/48-3*n**3/5, 61*n**3/240]
    e=math.sqrt(f*(2-f)); phi=math.radians(lat); lam=math.radians(lon-(zone*6-183))
    at=lambda v:0.5*math.log((1+v)/(1-v))
    t=math.sinh(at(math.sin(phi))-e*at(e*math.sin(phi)))
    xi=math.atan2(t,math.cos(lam)); eta=at(math.sin(lam)/math.sqrt(1+t*t))
    x,y=eta,xi
    for j in (1,2,3):
        x+=al[j-1]*math.cos(2*j*xi)*math.sinh(2*j*eta); y+=al[j-1]*math.sin(2*j*xi)*math.cosh(2*j*eta)
    N=0.9996*aa*y
    return 500000+0.9996*aa*x, (10_000_000+N if lat<0 else N)
def inv(E, N):
    lat, lon = -19.9, -45.0
    for _ in range(50):
        e0,n0=fwd(lat,lon); d=1e-6
        e1,n1=fwd(lat+d,lon); e2,n2=fwd(lat,lon+d)
        J=[[(e1-e0)/d,(e2-e0)/d],[(n1-n0)/d,(n2-n0)/d]]
        r=[E-e0,N-n0]; det=J[0][0]*J[1][1]-J[0][1]*J[1][0]
        dlat=(J[1][1]*r[0]-J[0][1]*r[1])/det; dlon=(-J[1][0]*r[0]+J[0][0]*r[1])/det
        lat+=dlat; lon+=dlon
    return lat, lon
def dm(v,w):
    a=abs(v); d=math.floor(a); m=(a-d)*60
    return f"{d:0{w}d}{m:011.8f}"
def gga(lat,lon,alt,sep):
    body=f"GPGGA,120000.00,{dm(lat,2)},{'S' if lat<0 else 'N'},{dm(lon,3)},{'W' if lon<0 else 'E'},4,16,0.7,{alt:.3f},M,{sep:.3f},M,1.0,0000"
    c=0
    for ch in body: c^=ord(ch)
    return f"${body}*{c:02X}"
# known local points (E, N, Z) from rodovia-exemplo, names 1, 5, 10
pts={"1":(612602.073,7795966.576,856.445)}
import re
xml=open('/home/paulo-lima/App Android/app-dwg/docs/store/exemplo/rodovia-exemplo.xml').read()
for m in re.finditer(r'<CgPoint name="(\d+)" code="\w+">([\d.]+) ([\d.]+) ([\d.]+)</CgPoint>', xml):
    pts[m.group(1)]=(float(m.group(3)), float(m.group(2)), float(m.group(4)))
# GNSS sees the site shifted, slightly rotated and scaled; ellipsoidal h = Z + Ngeoid(-5.5) + 0.25
th=math.radians(-0.02); s=1.00004; cx,cy=613000,7796300
GEO=-5.5; SEP=-4.0
for name in ("1","5","10","14"):
    E,N,Z=pts[name]; x,y=E-cx,N-cy
    Eg=cx+s*(math.cos(th)*x-math.sin(th)*y)+2.5; Ng=cy+s*(math.sin(th)*x+math.cos(th)*y)-1.8
    lat,lon=inv(Eg,Ng); h=Z+GEO+0.25
    open(f"cal_{name}.nmea","w").write("\r\n".join([gga(lat,lon,h-SEP,SEP)]*5)+"\r\n")
    print(name, lat, lon, fwd(lat,lon), (Eg,Ng))
# MAPGEO-like text grid around the site, N = -5.5 with a mild gradient
lat0,lon0=-20.5,-44.5
with open("mapgeo-teste.txt","w") as f:
    f.write("latitude longitude N\n")
    for i in range(21):
        for j in range(21):
            f.write(f"{lat0+i*0.05:.4f} {lon0+j*0.05:.4f} {GEO:.3f}\n")
