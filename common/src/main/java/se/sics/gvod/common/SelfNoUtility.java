/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.gvod.common;

import java.net.InetAddress;
import java.util.Set;
import se.sics.gvod.address.Address;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.Nat;

/**
 *
 * @author Jim
 */
public class SelfNoUtility extends SelfBase
{
    /**
     * This is like a cached self-address.
     * When I need to use my real VodAddress, i call self.getAddress().
     * Only the parents of a VodAddress may change, i can access
     * any other attribute of VodAddress using the cached object.
     */
    
   public SelfNoUtility(VodAddress addr) {
        this(addr.getNat(), addr.getIp(), addr.getPort(), addr.getId(), addr.getOverlayId());
    }
   
    public SelfNoUtility(Nat nat, InetAddress ip, int port, int nodeId, int overlayId) {
        super(nat, ip, port, nodeId, overlayId);
    }

    @Override
    public Utility getUtility() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateUtility(Utility utility) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VodDescriptor getDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Address> getParents() {
        return SelfFactory.getParents(nodeId);
    }
    
    @Override
    public boolean removeParent(Address parent) {
        return SelfFactory.removeParent(nodeId, parent);
    }

    @Override
    public void addParent(Address parent) {
        SelfFactory.addParent(nodeId, parent);
    }

    @Override
    public Self clone(int overlayId)  {
        return new SelfNoUtility(SelfFactory.getNat(nodeId), getIp(), port, nodeId, overlayId);
    }    
    
}