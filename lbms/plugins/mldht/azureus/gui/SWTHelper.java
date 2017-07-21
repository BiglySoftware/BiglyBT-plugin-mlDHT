/*
 *    This file is part of mlDHT. 
 * 
 *    mlDHT is free software: you can redistribute it and/or modify 
 *    it under the terms of the GNU General Public License as published by 
 *    the Free Software Foundation, either version 2 of the License, or 
 *    (at your option) any later version. 
 * 
 *    mlDHT is distributed in the hope that it will be useful, 
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 *    GNU General Public License for more details. 
 * 
 *    You should have received a copy of the GNU General Public License 
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>. 
 */
package lbms.plugins.mldht.azureus.gui;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import lbms.plugins.mldht.azureus.MlDHTPlugin;
import lbms.plugins.mldht.azureus.UIHelper;
import lbms.plugins.mldht.kad.*;
import lbms.plugins.mldht.kad.DHT.DHTtype;
import lbms.plugins.mldht.kad.utils.PopulationListener;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import com.biglybt.pif.ui.UIInstance;
import com.biglybt.pif.ui.UIManagerListener;
import com.biglybt.pif.ui.menus.MenuContext;
import com.biglybt.pif.ui.menus.MenuItem;
import com.biglybt.pif.ui.menus.MenuItemListener;
import com.biglybt.pif.ui.menus.MenuManager;
import com.biglybt.ui.swt.pif.UISWTInstance;
import com.biglybt.ui.swt.pif.UISWTStatusEntry;
import com.biglybt.ui.swt.pif.UISWTStatusEntryListener;

/**
 * @author Leonard
 * 
 */
public class SWTHelper implements UIHelper, UIManagerListener, PopulationListener, DHTStatusListener {
	
	private static final boolean	SHOW_STATUS_TEXT	= false;
	
	private UISWTStatusEntry	statusEntry;
	private UISWTInstance		swtInstance;
	private MlDHTPlugin			plugin;
	private List<DHTView>		views = new ArrayList<DHTView>();
	private List<MenuItem>		menu_items = new ArrayList<MenuItem>();

	public Image				dhtStatusEntryIcon;
	public Display				display;

	public SWTHelper(MlDHTPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void onPluginUnload () {
		
		for(DHTtype type : DHTtype.values()) {
			swtInstance.removeViews(UISWTInstance.VIEW_MAIN, DHTView.VIEWID+"."+type.shortName);
		}
		
		swtInstance.removeViews(UISWTInstance.VIEW_MAIN, DHTView.VIEWID);
		
		if (statusEntry != null) {
			statusEntry.destroy();
			statusEntry = null;
		}
		
		if (dhtStatusEntryIcon != null) {
			dhtStatusEntryIcon.dispose();
			dhtStatusEntryIcon = null;
		}
		
		for ( MenuItem mi: menu_items ){
			
			mi.remove();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.biglybt.pif.ui.UIManagerListener#UIAttached(com.biglybt.pif.ui.UIInstance)
	 */
	@Override
	public void UIAttached (UIInstance instance) {

		if (instance instanceof UISWTInstance) {
			swtInstance = (UISWTInstance) instance;
			display = swtInstance.getDisplay();

			try {
				statusEntry = swtInstance.createStatusEntry();
				dhtStatusEntryIcon = new Image(
						swtInstance.getDisplay(),
						MlDHTPlugin.class
								.getResourceAsStream("/lbms/plugins/mldht/azureus/gui/dhtIcon.png"));
				statusEntry.setImage(dhtStatusEntryIcon);
				statusEntry.setImageEnabled(true);
				
				statusEntry.setListener(
					new UISWTStatusEntryListener()
					{
							@Override
							public void
							entryClicked(
								UISWTStatusEntry entry )
							{
								plugin.showConfig();
							}
						});
				
				MenuContext menu_context = statusEntry.getMenuContext();
				
				MenuManager menu_manager = plugin.getPluginInterface().getUIManager().getMenuManager();
				
				for( final DHTtype type : DHTtype.values()){
				
					MenuItem item = menu_manager.addMenuItem( menu_context, "Views.plugins." + DHTView.VIEWID+"."+type.shortName + ".title");

					menu_items.add( item );
					
					item.addListener(
						new MenuItemListener()
						{
							@Override
							public void
							selected(
								MenuItem menu, Object target) 
							{
								swtInstance.openView(UISWTInstance.VIEW_MAIN, DHTView.VIEWID+"."+type.shortName,null );
							}
						});
				}
				
				
			} catch (Throwable e) {
				e.printStackTrace();
			}

			
			for(DHTtype type : DHTtype.values())
			{
				plugin.getDHT(type).getEstimator().addListener(this);
				plugin.getDHT(type).addStatusListener(this);
				DHTView view = new DHTView(plugin, swtInstance.getDisplay(),type);
				views.add(view);
				swtInstance.addView(UISWTInstance.VIEW_MAIN, DHTView.VIEWID+"."+type.shortName, view);
				if (plugin.isPluginAutoOpen(type.shortName)) {
					swtInstance.openMainView(DHTView.VIEWID+"."+type.shortName, view, null);
				}

			}

			updateStatusEntry();
		}
	}

	@Override
	public void UIDetached (UIInstance instance) {
		if (instance instanceof UISWTInstance) {
			onPluginUnload();

			swtInstance = null;
			display = null;
			views.clear();
			for(DHTtype type : DHTtype.values())
			{
				plugin.getDHT(type).getEstimator().removeListener(this);
				plugin.getDHT(type).removeStatusListener(this);
			}

		}
	}
	
	private void updateStatusEntry()
	{

		if (statusEntry != null) {
			DecimalFormat	format	= new DecimalFormat();
			
			final StringBuilder text = new StringBuilder("mlDHT: ");
			final StringBuilder tooltip = new StringBuilder( plugin.getMessageText( "mldht.node.estimate" ));
			for(DHTtype type : DHTtype.values())
			{
				DHT dht = plugin.getDHT(type);
				DHTStatus status = dht.getStatus();
				text.append(" "+type.shortName+": ");
				tooltip.append(" "+type.shortName+": ");
				if(status == DHTStatus.Running && dht.getEstimator().getEstimate() != 0)
				{
					text.append("\u2714");	// unicode not avail on some platforms
					tooltip.append(format.format(dht.getEstimator().getEstimate()));
				} else {
					text.append("\u2718");	// unicode not avail on some platforms
					
					String status_str;
					
					if ( status == DHTStatus.Initializing ){
						status_str = plugin.getMessageText( "mldht.initializing" );
					}else if (status == DHTStatus.Running){
						status_str = plugin.getMessageText( "mldht.running" );
					}else{
						status_str = plugin.getMessageText( "mldht.stopped" );
					}
					
					tooltip.append(status_str);
				}
				
			}

			if (display != null && !display.isDisposed()) {
				display.asyncExec(new Runnable() {
					@Override
					public void run () {
						if ( statusEntry != null ){
							if ( SHOW_STATUS_TEXT ){
								statusEntry.setText(text.toString());
							}else{
								statusEntry.setText( "mlDHT" );
							}
							statusEntry.setTooltipText(tooltip.toString());
							statusEntry.setVisible(plugin.getPluginInterface().getPluginconfig().getPluginBooleanParameter("showStatusEntry"));
						}
					}
				});
			}

			
		}
		
	}
	
	@Override
	public void statusChanged(DHTStatus newStatus, DHTStatus oldStatus) {
		updateStatusEntry();
	}
	
	@Override
	public void populationUpdated(long estimatedPopulation) {
		updateStatusEntry();
	}
}
