package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Vector3f;
import java.lang.reflect.Proxy;

/** Inspect the actual native banner submissions, including ItemTransform's half-block origin. */
final class NpcStandardGripGameTests {
    record Contact(double palmToPole,double clothToHand,double clothToFace) {}
    private NpcStandardGripGameTests() {}

    static Contact check(NpcRenderer renderer,NpcRenderState state) {
        var side=state.standardArm();
        var item=side==HumanoidArm.RIGHT?state.rightHandItemStack:state.leftHandItemStack;
        return check(renderer,state,item);
    }

    static Contact check(NpcRenderer renderer,NpcRenderState state,net.minecraft.world.item.ItemStack item) {
        var parent=renderer.getModel();
        var side=state.standardArm();
        if(!state.carryingStandard||!(item.getItem() instanceof net.minecraft.world.item.BannerItem))
            throw new IllegalStateException("No real standard to inspect");
        var poses=new PoseStack();NpcRoleItemInHandLayer.standardGrip(parent,state,side,poses);
        var hand=side==HumanoidArm.RIGHT?parent.rightArm:parent.leftArm;
        var palm=new Vector3f();double[] handBounds=emptyBounds();boolean[] foundHand={false};
        hand.visit(new PoseStack(),(pose,path,index,cube)->{
            if(!path.isEmpty()||index!=0)return;
            // Derive the physical palm from the original baked arm cube, independently of the grip helper.
            pose.pose().transformPosition(new Vector3f((cube.minX+cube.maxX)/32,(cube.maxY-1)/16,
                    (cube.minZ+cube.maxZ)/32),palm);
            include(handBounds,pose,cube.minX,cube.maxY-2,cube.minZ,cube.maxX,cube.maxY,cube.maxZ);
            foundHand[0]=true;
        });
        var bottom=new Vector3f();var top=new Vector3f();boolean[] foundPole={false},foundCloth={false};
        double[] clothBounds=emptyBounds();
        var collector=(SubmitNodeCollector)Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class},(proxy,method,args)->{
                    if(method.getName().equals("order"))return proxy;
                    if(method.getName().equals("toString"))return "NativeStandardContactCapture";
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    if(!method.getName().equals("submitModel"))return null;
                    var submitted=(PoseStack)args[2];
                    if(args[0] instanceof BannerModel model){
                        model.setupAnim((net.minecraft.util.Unit)args[1]);
                        model.root().visit(submitted,(pose,path,index,cube)->{
                            if(!path.endsWith("/pole"))return;
                            float x=(cube.minX+cube.maxX)/32,z=(cube.minZ+cube.maxZ)/32;
                            pose.pose().transformPosition(new Vector3f(x,cube.maxY/16,z),bottom);
                            pose.pose().transformPosition(new Vector3f(x,cube.minY/16,z),top);foundPole[0]=true;
                        });
                    }else if(args[0] instanceof BannerFlagModel flag){
                        flag.setupAnim((Float)args[1]);
                        flag.root().visit(submitted,(pose,path,index,cube)->
                                include(clothBounds,pose,cube.minX,cube.minY,cube.minZ,cube.maxX,cube.maxY,cube.maxZ));
                        foundCloth[0]=true;
                    }
                    return null;
                });
        renderer.carriedStandard().submit(item,poses,collector,0xF000F0,0);
        if(!foundHand[0]||!foundPole[0]||!foundCloth[0])throw new IllegalStateException("Missing native hand/pole/cloth geometry");
        var axis=new Vector3f(top).sub(bottom);float length=axis.length();axis.div(length);
        if(Math.abs(axis.x())>.00001||axis.y()>-.9999||Math.abs(axis.z())>.00001)
            throw new IllegalStateException("Submitted native pole is not upright");
        float along=new Vector3f(palm).sub(bottom).dot(axis);
        var closest=new Vector3f(axis).mul(along).add(bottom);
        double error=closest.distance(palm);
        if(error>1.0/512||along<0||along>length)
            throw new IllegalStateException("Native pole misses the physical palm: "+error+" / "+along+" / "+length);
        double clothGap=handBounds[1]-clothBounds[4];
        if(clothGap<0)throw new IllegalStateException("Banner cloth hides the palm: "+clothGap);
        double headMin=parent.head.x/16-.25*parent.head.xScale,headMax=parent.head.x/16+.25*parent.head.xScale;
        double faceGap=Math.max(clothBounds[0]-headMax,headMin-clothBounds[3]);
        if(faceGap<.01)throw new IllegalStateException("Banner overlaps the face in the front view: "+faceGap);
        return new Contact(error,clothGap,faceGap);
    }

    private static double[] emptyBounds(){return new double[]{1e9,1e9,1e9,-1e9,-1e9,-1e9};}
    private static void include(double[] bounds,PoseStack.Pose pose,float x0,float y0,float z0,float x1,float y1,float z1){
        for(float x:new float[]{x0,x1})for(float y:new float[]{y0,y1})for(float z:new float[]{z0,z1}){
            var p=pose.pose().transformPosition(new Vector3f(x/16,y/16,z/16));
            bounds[0]=Math.min(bounds[0],p.x());bounds[1]=Math.min(bounds[1],p.y());bounds[2]=Math.min(bounds[2],p.z());
            bounds[3]=Math.max(bounds[3],p.x());bounds[4]=Math.max(bounds[4],p.y());bounds[5]=Math.max(bounds[5],p.z());
        }
    }
}
