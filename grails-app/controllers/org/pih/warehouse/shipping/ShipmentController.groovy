package org.pih.warehouse.shipping;

import java.io.Serializable;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.List;

import grails.converters.JSON
import grails.validation.ValidationException;
import groovy.sql.Sql;
import au.com.bytecode.opencsv.CSVWriter;

import org.apache.commons.collections.FactoryUtils;
import org.apache.commons.collections.list.LazyList;
import org.codehaus.groovy.grails.validation.Validateable;
import org.pih.warehouse.core.Comment;
import org.pih.warehouse.core.Document;
import org.pih.warehouse.core.Event;
import org.pih.warehouse.core.EventCode;
import org.pih.warehouse.core.EventType;
import org.pih.warehouse.core.Location;
import org.pih.warehouse.core.Person;
import org.pih.warehouse.core.User;
import org.pih.warehouse.inventory.InventoryItem;
import org.pih.warehouse.inventory.Warehouse;
import org.pih.warehouse.product.Product;
import org.pih.warehouse.receiving.Receipt;
import org.pih.warehouse.receiving.ReceiptItem;
import org.pih.warehouse.core.ListCommand;

import com.ocpsoft.pretty.time.PrettyTime;


class ShipmentController {
	
	def scaffold = Shipment
	def shipmentService
	def inventoryService;
	
	def dataSource
	def sessionFactory
	
	def create = {
		def shipmentInstance = new Shipment()
		shipmentInstance.properties = params
		
		if (params.type == "incoming") { 
			shipmentInstance.destination = session.warehouse;
		}
		else if (params.type == "outgoing") { 
			shipmentInstance.origin = session.warehouse;
		}		
		//return [shipmentInstance: shipmentInstance]
		render(view: "create", model: [ shipmentInstance : shipmentInstance,
		warehouses : Warehouse.list(), eventTypes : EventType.list()]);
	}
	
	def save = {
		def shipmentInstance = new Shipment(params)
		
		if (shipmentInstance.save(flush: true)) {
			
			// Try to add the initial event
			def eventType = EventType.get(params.eventType.id);
			if (eventType) {
				def shipmentEvent = new Event(eventType: eventType, eventLocation: session.warehouse, eventDate: new Date())
				shipmentInstance.addToEvents(shipmentEvent).save(flush:true);
			}
			flash.message = "${warehouse.message(code: 'default.created.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), shipmentInstance.id])}"
			redirect(action: "showDetails", id: shipmentInstance.id)
		}
		else {
			//redirect(action: "create", model: [shipmentInstance: shipmentInstance], params: [type:params.type])
			render(view: "create", model: [shipmentInstance : shipmentInstance,
			warehouses : Warehouse.list(), eventTypes : EventType.list()]);
		}
	}
	
	def update = {	
		log.info params
		
		def shipmentInstance = Shipment.get(params.id)
		if (shipmentInstance) {
			if (params.version) {
				def version = params.version.toLong()
				if (shipmentInstance.version > version) {					
					shipmentInstance.errors.rejectValue("version", "default.optimistic.locking.failure", [warehouse.message(code: 'shipment.label', default: 'Shipment')] as Object[], "Another user has updated this Shipment while you were editing")
					render(view: "editDetails", model: [shipmentInstance: shipmentInstance])
					return
				}
			}			
			
			// Bind request parameters 
			shipmentInstance.properties = params
			
			// -- Processing shipment method  -------------------------
			log.info "autocomplete shipment method: " + params
			// Create a new shipment method if one does not exist
			def shipmentMethod = shipmentInstance.shipmentMethod;
			if (!shipmentMethod) {
				shipmentMethod = new ShipmentMethod();
			}
			
			// If there's an ID but no name, it means we want to remove the shipper and shipper service
			if (!params.shipperService.name) { 			
				shipmentMethod.shipper = null
				shipmentMethod.shipperService = null
			}
			// Otherwise we set the selected accordingly
			else if (params.shipperService.id && params.shipperService.name) { 
				def shipperService = ShipperService.get(params.shipperService.id);
				if (shipperService) { 
					shipmentMethod.shipperService = shipperService;
					shipmentMethod.shipper = shipperService.shipper;
				}
			}
			// We work with and save the shipmentMethod instance in order to avoid a transient object exception
			// that occurs when setting the destination above and saving the shipment method within the shipment
			shipmentInstance.shipmentMethod = shipmentMethod;
			shipmentInstance.shipmentMethod.save(flush:true);
			
			// -- Processing destination  -------------------------
			// Reset the destination to null
			if (!params.safeDestination.name) {
				shipmentInstance.destination = null;
			}
			// Assign a destination if one was selected
			else if (params.safeDestination.id && params.safeDestination.name) {
				def destination = Location.get(params.safeDestination.id);
				if (destination && params.safeDestination.name == destination.name) // if it exists
				shipmentInstance.destination = destination;
			}
			
			// -- Processing carrier  -------------------------
			// This is necessary because Grails seems to be binding things incorrectly.  If we just let 
			// Grails do the binding by itself, it tries to change the ID of the 'carrier' that is already
			// associated with the shipment, rather than changing the 'carrier' object associated with 
			// the shipment.
			
			// Reset the carrier
			if (!params.safeCarrier.name) {
				shipmentInstance.carrier = null;
			}
			// else if the person is found and different from the current one, then we use that person
			else if (params.safeCarrier.id && params.safeCarrier.name) {
				def safeCarrier = Person.get(params.safeCarrier.id);				
				if (safeCarrier && safeCarrier?.name != shipmentInstance?.carrier?.name)
				shipmentInstance.carrier = safeCarrier;
			}
			// else if only the name is provided, we need to create a new person
			else { 
				def safeCarrier = convertStringToPerson(params.safeCarrier.name);
				if (safeCarrier) { 
					safeCarrier.save(flush:true)
					shipmentInstance.carrier = safeCarrier;
				}
			}
			
			if (!shipmentInstance.hasErrors() && shipmentInstance.save(flush: true)) {
				flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), shipmentInstance.id])}"
				redirect(action: "showDetails", id: shipmentInstance.id)
			}
			else {
				render(view: "editDetails", model: [shipmentInstance: shipmentInstance])
			}
		}
		else {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: "list")
		}
	}
	
	
	
	def showDetails = {
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			def eventTypes =  org.pih.warehouse.core.EventType.list();
			def shipmentWorkflow = shipmentService.getShipmentWorkflow(shipmentInstance)
			[shipmentInstance: shipmentInstance, shipmentWorkflow: shipmentWorkflow, shippingEventTypes : eventTypes]
		}
	}
	
	
	/**
	def showDetailsAlt = {
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			[shipmentInstance: shipmentInstance]
		}
	}
	**/
	
	def editDetails = {
		log.info params
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			[shipmentInstance: shipmentInstance]
		}
	}
	
	def sendShipment = {
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			// handle a submit
			if ("POST".equalsIgnoreCase(request.getMethod())) { 				
				// make sure a shipping date has been specified and that is not the future
				if (!params.actualShippingDate || Date.parse("MM/dd/yyyy", params.actualShippingDate) > new Date()) {
					flash.message = "${warehouse.message(code: 'shipping.specifyValidShipmentDate.message')}"
					def shipmentWorkflow = shipmentService.getShipmentWorkflow(shipmentInstance)	
					render(view: "sendShipment", model: [shipmentInstance: shipmentInstance, shipmentWorkflow: shipmentWorkflow])
					return
				}
				
				// create the list of email recipients
				def emailRecipients = new HashSet()				
				params.emailRecipientId?.each ( { emailRecipients = emailRecipients + Person.get(it) } )
				
				// send the shipment
				shipmentService.sendShipment(shipmentInstance, params.comment, session.user, session.warehouse, 
												Date.parse("MM/dd/yyyy", params.actualShippingDate), emailRecipients);
				
				if (!shipmentInstance.hasErrors()) { 
					flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), shipmentInstance.id])}"
					redirect(action: "showDetails", id: shipmentInstance?.id)
				}
			}
			
			// populate the model and render the page
			def shipmentWorkflow = shipmentService.getShipmentWorkflow(shipmentInstance)
			render(view: "sendShipment", model: [shipmentInstance: shipmentInstance, shipmentWorkflow: shipmentWorkflow])
		}
	}
	
	
	def deleteShipment = {
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(controller: "dashboard", action: "index");
			return;
		}
		else {
			if ("POST".equalsIgnoreCase(request.getMethod())) {	
				//shipmentInstance.shipmentItems.clear();
				//shipmentInstance.containers.clear();
				Shipment.withTransaction { tx -> 
					shipmentInstance.delete();
					
					
					//tx.setRollbackOnly();
				}
				
				flash.message = "${warehouse.message(code: 'default.deleted.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), shipmentInstance.id])}"
				redirect(controller: "dashboard", action: "index")
				return;
			}
		}
		[shipmentInstance:shipmentInstance]
	}
	
	def receiveShipment = {
		log.info "params: " + params
		def receiptInstance
		def shipmentInstance = Shipment.get(params.shipmentId)		
		
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: "listReceiving")
		}
		else {			
			if ("POST".equalsIgnoreCase(request.getMethod())) {			
				receiptInstance = new Receipt(params)
				
				// associate the receipt with the shipment
				shipmentInstance.receipt = receiptInstance
				receiptInstance.shipment = shipmentInstance
				
				// check for errors
				if(receiptInstance.hasErrors() || !receiptInstance.validate()) {
					render(view: "receiveShipment",  model: [shipmentInstance: shipmentInstance, receiptInstance:receiptInstance])
					return
				}
				
				// actually process the receipt
				shipmentService.receiveShipment(shipmentInstance, params.comment, session.user, session.warehouse);
				
				if (!shipmentInstance.hasErrors()) {
					flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), shipmentInstance.id])}"
					redirect(action: "showDetails", id: shipmentInstance?.id)
					return
				}
				redirect(controller:"shipment", action : "showDetails", params : [ "id" : shipmentInstance.id ?: '' ])
			}
			else { 
				if (shipmentInstance.receipt) {
					receiptInstance = shipmentInstance.receipt
				}
				// If no existing receipt, instantiate the model class to be used 
				else {
					receiptInstance = new Receipt(recipient:shipmentInstance?.recipient);
					receiptInstance.receiptItems = new HashSet()
				
					shipmentInstance.shipmentItems.each {										
						ReceiptItem receiptItem = new ReceiptItem(it.properties);
						receiptItem.setQuantityShipped (it.quantity);
						receiptItem.setQuantityReceived (it.quantity);				
						receiptItem.setLotNumber(it.lotNumber);
						receiptInstance.receiptItems.add(receiptItem);           // use basic "add" method to avoid GORM because we don't want to persist yet
					}	
				}
			}
		}
		render(view: "receiveShipment", model: [shipmentInstance: shipmentInstance, receiptInstance:receiptInstance])
	}
	
	def showPackingList = { 
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			[shipmentInstance: shipmentInstance]
		}
	}
	
	def downloadPackingList = { 
		def shipmentInstance = Shipment.get(params.id)
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			//List<String[]> allElements = new ArrayList<String[]>();
			//shipmentInstance.getAllShipmentItems().each { 
			//	def row = it.name
			//}
			String query = """
				select  
					container.name,  
					container.height, 
					container.width, 
					container.length, 
					container.volume_units, 
					container.weight, 
					container.weight_units,
					shipment_item.quantity,
					product.name,
					shipment_item.serial_number
				from shipment, container, shipment_item, product
				where shipment.id = container.shipment_id
				and shipment_item.container_id = container.id
				and shipment_item.product_id = product.id 
				and shipment.id = ${params.id}"""
			
			StringWriter sw = new StringWriter();
			CSVWriter writer = new CSVWriter(sw);
			Sql sql = new Sql(sessionFactory.currentSession.connection())	
			
			String [] colArray = new String[6];
			colArray.putAt(0, "unit");
			colArray.putAt(1, "dimensions");
			colArray.putAt(2, "weight");
			colArray.putAt(3, "qty");
			colArray.putAt(4, "item");
			colArray.putAt(5, "serial number");
			writer.writeNext(colArray);
			sql.eachRow(query) { row -> 
				
				def rowArray = new String[6];
				rowArray.putAt(0, row[0]);
				rowArray.putAt(1, (row[1])?row[1]:"0" + "x" + (row[2])?row[2]:"0" + "x" + (row[3])?row[3]:"0" + " " + (row[4])?row[4]:"");
				rowArray.putAt(2, row[5] + " " + row[6] );
				rowArray.putAt(3, row[7]);
				rowArray.putAt(4, row[8]);
				rowArray.putAt(5, row[9]);
				writer.writeNext(rowArray);
			}
			
			//writer.writeAll(resultSet, false);
			log.info "results: " + sw.toString();
			response.setHeader("Content-disposition", "attachment; filename=PackingList.csv");
			render(contentType: "text/csv", text: sw.toString());			
			sql.close();
			//resultSet.close();
			
			
		}
	}
	
	
	def editContents = {
		def shipmentInstance = Shipment.get(params.id)
		def containerInstance = Container.get(params?.container?.id);
		
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: (params.type == "incoming") ? "listReceiving" : "listShipping")
		}
		else {
			
			if (!containerInstance && shipmentInstance?.containers) { 
				containerInstance = shipmentInstance.containers.iterator().next();
			}
			[shipmentInstance: shipmentInstance, containerInstance: containerInstance]
		}
	}
	
	/**
	def listShipments = { 		
		[ shipments : shipmentService.getShipments() ]
	}
	**/
	
	def listReceiving = { 
		def destination = Location.get(session.warehouse.id)
		def shipmentType = params.shipmentType ? ShipmentType.get(params.shipmentType) : null
		def origin = params.origin ? Location.get(params.origin) : null
		def statusCode = params.status ? Enum.valueOf(ShipmentStatusCode.class, params.status) : null
		def statusStartDate = params.statusStartDate ? Date.parse("MM/dd/yyyy", params.statusStartDate) : null
		def statusEndDate = params.statusEndDate ? Date.parse("MM/dd/yyyy", params.statusEndDate) : null
					
		def shipments = shipmentService.getShipments(shipmentType, origin, destination, statusCode, statusStartDate, statusEndDate)

		// sort by status
		shipments = shipments.sort( { a, b -> 
			a.getStatus() <=> b.getStatus()
		} )
		
		[ shipments:shipments, shipmentType:shipmentType?.id, origin:origin?.id, status:statusCode?.name, 
				statusStartDate:statusStartDate, statusEndDate:statusEndDate ]
	}
	
	
	/**
	def listShippingByDate = { 
		def currentLocation = Location.get(session.warehouse.id);
		
		def outgoingShipments = params.sort ? shipmentService.getAllShipments(params.sort, params.order) : 
		shipmentService.getAllShipments('expectedShippingDate','asc')  // probably could default on something better than this
		
		// filter by origin location
		outgoingShipments = outgoingShipments.findAll( {it.origin == currentLocation
		} )
		
		def formatter = new PrettyTime();		
		def groupBy = (params.groupBy) ? params.groupBy : "lastUpdated";
		
		outgoingShipments = outgoingShipments.sort { it[groupBy]
		}.reverse();
		def shipmentInstanceMap = outgoingShipments.groupBy { it[groupBy] ? formatter.format(it[groupBy]) : "Empty"
		}
		render(view: "listShippingByDate", model: [ shipmentInstanceMap : shipmentInstanceMap ]);		
	}
	**/
	
	
	def listShippingByType = {
		def currentLocation = Location.get(session.warehouse.id);
		def outgoingShipments = shipmentService.getShipmentsByOrigin(currentLocation);		
		outgoingShipments = outgoingShipments.sort { it.lastUpdated
		}.reverse();
		def shipmentInstanceMap = outgoingShipments.groupBy { it.shipmentType
		}
		render (view: "listShippingByDate", model: [ shipmentInstanceMap : shipmentInstanceMap ]);
	}
	
	def listShipping = {	
		def origin = Location.get(session.warehouse.id)
		def shipmentType = params.shipmentType ? ShipmentType.get(params.shipmentType) : null
		def destination = params.destination ? Location.get(params.destination) : null
		def statusCode = params.status ? Enum.valueOf(ShipmentStatusCode.class, params.status) : null
		def statusStartDate = params.statusStartDate ? Date.parse("MM/dd/yyyy", params.statusStartDate) : null
		def statusEndDate = params.statusEndDate ? Date.parse("MM/dd/yyyy", params.statusEndDate) : null
					
		def shipments = shipmentService.getShipments(shipmentType, origin, destination, statusCode, statusStartDate, statusEndDate)
		
		// sort by event status, event date, and expecting shipping date
		shipments = shipments.sort( { a, b -> 
			def diff = a.getStatus() <=> b.getStatus() 
			if (diff == 0) {
				diff = a.expectedShippingDate <=> b.expectedShippingDate
			}
			return diff
		} )
		
		[ shipments:shipments, shipmentType:shipmentType?.id, destination:destination?.id, status:statusCode?.name, 
				statusStartDate:statusStartDate, statusEndDate:statusEndDate ]	
	}	
	
	/**
	def list = { 
		def browseBy = params.id;
		def currentLocation = Location.get(session.warehouse.id);    	
		log.debug ("current location" + currentLocation.name)    	
		def allShipments = shipmentService.getShipmentsByLocation(currentLocation);
		def incomingShipments = shipmentService.getShipmentsByDestination(currentLocation);	
		def outgoingShipments = shipmentService.getShipmentsByOrigin(currentLocation);			
		def shipmentInstanceList = ("incoming".equals(browseBy)) ? incomingShipments : 
		("outgoing".equals(browseBy)) ? outgoingShipments : allShipments;		
		// Arrange shipments by status 
		def shipmentListByStatus = new HashMap<String, ListCommand>();
		allShipments.each {
			def shipmentList = shipmentListByStatus[it.mostRecentStatus];
			if (!shipmentList) {
				shipmentList = new ListCommand(category: it.mostRecentStatus, color: "#ddd", 
				sortOrder: 0, objectList: new ArrayList());
			}
			shipmentList.objectList.add(it);	
			shipmentListByStatus.put(it.mostRecentStatus, shipmentList)
		}
		
		// Get a count of shipments by status		 
		// QUERY: select shipment_status.id, count(*) from shipment group by shipment_status.id 
		
		def criteria = Shipment.createCriteria()
		def results = criteria {			
			projections {
				groupProperty("shipmentType")
				count("shipmentType", "shipmentCount") //Implicit alias is created here !
			}
			//order 'myCount'
		}			
		
		[ 	results : results, shipmentInstanceList : shipmentInstanceList,
		shipmentInstanceTotal : allShipments.size(), shipmentListByStatus : shipmentListByStatus,
		incomingShipmentCount : incomingShipments.size(), outgoingShipmentCount : outgoingShipments.size()
		]
	}
	*/
	
	def saveItem = {     		
		log.info params;    	
		def shipment = Shipment.get(params.id);		
		def container = Container.get(params.container.id);
		def product = Product.get(params.selectedItem.id)
		def recipient = Person.get(params.recipient.id);		
		def quantity = (params.quantity) ? Integer.parseInt(params.quantity.trim()) : 1;
		def shipmentItem = null;
		
		// Create a new unverified product
		if (!product) { 			
			product = new Product(name: params.selectedItem.name);			
			if (!product.hasErrors() && product.save(flush: true)) {
				flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'product.label', default: 'Product'), product.id])}"
				//redirect(action: "editContents", id: shipment.id, params: ["container.id": container?.id])
			}
			else {
				// Encountered an error with saving the product
				redirect(action: "editContents", id: shipment.id, params: ["container.id": container?.id])
				return;
			}
		}	
		
		// Add item to container if product doesn't already exist
		if (container) { 
			def oldQuantity = 0;
			def newQuantity = 0;
			boolean found = false;
			container.shipmentItems.each { 
				if (it.product == product) { 
					oldQuantity = it.quantity;					
					it.quantity += quantity;
					newQuantity = it.quantity;
					it.save();
					found = true;
				}
			}			
			if (!found) { 			
				shipmentItem = new ShipmentItem(product: product, 
				quantity: quantity, 
				recipient: recipient,
				container: containerInstance);
				
				//container.addToShipmentItems(shipmentItem).save(flush:true);
				container.addToShipmentItems(shipmentItem).save(flush:true);
			}
			else { 
				flash.message = "${warehouse.message(code: 'shipping.modifiedQuantityOfExistingShipment.message', args: [format.product(product:product), oldQuantity, newQuantity])}"
			}
		}
		
		redirect action: "editContents", id: shipment?.id, params: ["container.id": container?.id];
	}    
	
	
	
	def addContainer = { 		
		log.debug params 		
		def shipment = Shipment.get(params.shipmentId);   	
		def containerType = ContainerType.get(params.containerTypeId);    	
		def containerName = (params.name) ? params.name : containerType.name + " " + (shipment.getContainers().size() + 1);
		def container = new Container(name: containerName, weight: params.weight, weightUnits: params.weightUnits, containerType: containerType);
		shipment.addToContainers(container);
		redirect(action: 'editContents', id: params.shipmentId)
	}
	
	/*
	 def editContainer = {
	 def container = Shipment.get(params.containerId);
	 def containerType = ContainerType.get(params.containerTypeId);
	 def containerName = (params.name) ? params.name : containerType.name + " " + shipment.getContainers().size()
	 def container = new Container(name: containerName, weight: params.weight, units: params.weightUnits, containerType: containerType);
	 container.save(flush:true);
	 flash.message = "Added a new piece to the shipment";
	 redirect(action: 'show', id: params.shipmentId)
	 }*/
	
	
	def editContainer = {		
		
		log.info params
		
		def shipmentInstance = Shipment.get(params.shipmentId)		
		def containerInstance = Container.get(params.containerId)
		if (containerInstance) {
			
			containerInstance.properties = params
			
			Iterator iter = containerInstance.shipmentItems.iterator()
			while (iter.hasNext()) {
				def item = iter.next()
				log.info item.product.name + " " + item.quantity;
				
				if (item.quantity == 0) {
					item.delete();
					//containerInstance.removeFromShipmentItems(item);
					iter.remove();					
				}
			}
			
			// If the user removed the recipient, we need to make sure that the whole object is removed (not just the ID)
			for (def shipmentItem : containerInstance?.shipmentItems) { 
				if (!shipmentItem?.recipient?.id) { 
					log.info("item recipient: " + shipmentItem?.recipient?.id)
					shipmentItem.recipient = null;
				}
			}
			
			if (!containerInstance.hasErrors() && containerInstance.save(flush: true)) {
				flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'container.label', default: 'Container'), containerInstance.id])}"
				redirect(action: "editContents", id: shipmentInstance.id, params: ["container.id" : params.containerId])
			}
			else {
				flash.message = "${warehouse.message(code: 'shipping.couldNotEditContainer.message')}"
				redirect(action: "showDetails", id: shipmentInstance.id, params: ["containerId" : params.containerId])
				//render(view: "edit", model: [containerInstance: containerInstance])
			}
		}
		else {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'container.label', default: 'Container'), params.containerId])}"
			redirect(action: "showDetails", id: shipmentInstance.id, params: ["containerId" : params.containerId])
			//redirect(action: "list")
		}
	}
	
	
	
	def copyContainer = { 
		def container = Container.get(params.id);  
		def shipment = Shipment.get(params.shipmentId);   	
		
		if (container && shipment) { 		
			def numCopies = (params.copies) ? Integer.parseInt( params.copies ) : 1
			int index = (shipment?.containers)?(shipment.containers.size()):1;
			/*try { 
			 index = Integer.parseInt(container.name);
			 } catch (NumberFormatException e) {
			 log.warn("The given value " + params.name + " is not an integer");
			 }*/
			
			
			while ( numCopies-- > 0 ) {
				def containerCopy = new Container(container.properties);
				containerCopy.id = null;
				containerCopy.name = "" + (++index);
				containerCopy.containerType = container.containerType;
				containerCopy.weight = container.weight;
				//containerCopy.dimensions = container.dimensions;
				containerCopy.shipmentItems = null;
				containerCopy.save(flush:true);
				
				container.shipmentItems.each { 
					def shipmentItemCopy = new ShipmentItem(
					product: it.product, 
					quantity: it.quantity,  
					recipient: it.recipient,
					container: containerCopy);
					//containerCopy.addToShipmentItems(shipmentItemCopy).save(flush:true);
					containerCopy.shipment.addToShipmentItems(shipmentItem).save(flush:true);
				}    		
				shipment.addToContainers(containerCopy).save(flush:true);
			}
				flash.message = "${warehouse.message(code: 'shipping.copiedContainerSuccessfully.message')}"
		} else { 
			flash.message = "${warehouse.message(code: 'shipping.unableToCopyPackage.message')}"
		}
		
		redirect(action: 'showDetails', id: params.shipmentId)
	}    
	
	
	def addDocument = { 
		log.info params
		def shipmentInstance = Shipment.get(params.id);
		def documentInstance = Document.get(params?.document?.id);
		if (!documentInstance) { 
			documentInstance = new Document();
		}
		if (!shipmentInstance) { 
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
			redirect(action: "listShipping")
		}
		render(view: "addDocument", model: [shipmentInstance : shipmentInstance, documentInstance : documentInstance]);
	}
	
	def editDocument = {
		def shipmentInstance = Shipment.get(params?.shipmentId);
		def documentInstance = Document.get(params?.documentId);
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.shipmentId])}"
			redirect(action: "listShipping")
		}
		if (!documentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'document.label', default: 'Document'), params.documentId])}"
			redirect(action: "showDetails", id: shipmentInstance?.id)
		}
		render(view: "addDocument", model: [shipmentInstance : shipmentInstance, documentInstance : documentInstance]);
	}

	/*
	def saveDocument = { 
		
		log.info params 
	
		render(view: )	
	}
	*/
	
	
	
	def addComment = {
		log.debug params;
		def shipmentInstance = Shipment.get(params.id)
		render(view: "addComment", model: [shipmentInstance : shipmentInstance, comment : new Comment()]);
		
		//def recipient = (params.recipientId) ? User.get(params.recipientId) : null;
		//def comment = new Comment(comment: params.comment, commenter: session.user, recipient: recipient)
		//if (shipment) {
		//	shipment.addToComments(comment).save();
		//	flash.message = "Added comment '${params.comment}'to shipment $shipment.id";
		//}
		//redirect(action: 'addComment', id: params.shipmentId)
	}
	
	/**
	 * This action is used to render the form page used to add a 
	 * new package/container to a shipment.
	 */
	def addPackage = {		
		def shipmentInstance = Shipment.get(params.id);
		//def containerType = ContainerType.findByName(params?.containerType?.name); 
		
		def containerName = (shipmentInstance?.containers) ? String.valueOf(shipmentInstance?.containers?.size() + 1) : "1";
		def containerInstance = new Container(name : containerName);
		
		render(view: "addPackage", model: [shipmentInstance : shipmentInstance, containerInstance : containerInstance]);
		
	}
	
	
	/**
	 * This closure is used to process the 'add package' form.
	 */
	def savePackage = {
		
		log.info "params " + params;
		
		def shipmentInstance = Shipment.get(params.shipmentId);
		def parentContainerInstance = Container.get(params?.parentContainer?.id);
		
		def containerInstance = new Container(params);
		if (containerInstance && shipmentInstance) {	
			shipmentInstance.addToContainers(containerInstance);
			if (!shipmentInstance.hasErrors() && shipmentInstance.save(flush: true)) {
				flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'container.label', default: 'Container'), containerInstance.id])}"
				if (parentContainerInstance) { 
					parentContainerInstance.addToContainers(containerInstance).save(flush: true);
				}
				//container.containerType = ContainerType.get(params.containerType.id);
				//container.name = (shipmentInstance?.containers) ? String.valueOf(shipmentInstance.containers.size() + 1) : "1";			
				redirect(action: "editContents", id: shipmentInstance.id, params: ["container.id" : containerInstance.id])
			}
			else {
				//flash.message = "Could not save container"				
				render(view: "addPackage", model: [shipmentInstance:shipmentInstance, containerInstance:containerInstance])
			}
		} else { 		
			redirect(action: 'showDetails', id: params.shipmentId);
		}
	}
	
	
	def saveComment = { 
		def shipmentInstance = Shipment.get(params.shipmentId);
		def recipient = (params.recipientId) ? User.get(params.recipientId) : null;
		def comment = new Comment(comment: params.comment, sender: session.user, recipient: recipient)
		if (shipmentInstance) { 
			shipmentInstance.addToComments(comment).save(flush:true);
			flash.message = "${warehouse.message(code: 'shipping.addedCommentToShipment.message', arg=[params.comment,shipmentInstance.name])}"
		}
		redirect(action: 'showDetails', id: params.shipmentId);
	}
	
	
	
	
	def editItem = { 
		def item = ShipmentItem.get(params.id);
		def container = item.getContainer();
		def shipmentId = container.getShipment().getId();
		if (item) {
			item.quantity = Integer.parseInt(params.quantity);
			item.save();
			flash.message = "${warehouse.message(code: 'shipping.addedCommentToShipment.message', arg=[params.id, container.name])}"
			redirect(action: 'editContents', id: shipmentId)
		}
		else {
			flash.message = "${warehouse.message(code: 'shipping.couldNotEditItemFromContainer.message', arg=[params.id])}"
			redirect(action: 'showDetails', id: shipmentId, params: [container.id, container.id])
		}
	}
	
	
	def deleteDocument = { 
		def document = Document.get(params.id);
		def shipment = Shipment.get(params.shipmentId);
		if (shipment && document) { 	    	
			shipment.removeFromDocuments(document).save(flush:true);
			document.delete();	    	    	
				flash.message = "${warehouse.message(code: 'shipping.deletedDocumentFromShipment.message', arg=[params.id])}"
		}
		else { 
			flash.message = "${warehouse.message(code: 'shipping.couldNotRemoveDocumentFromShipment.message', arg=[params.id])}"
		}		
		redirect(action: 'showDetails', id: params.shipmentId)
	}
	
	def deleteEvent = {		
		def event = Event.get(params.id);
		def shipment = Shipment.get(params.shipmentId);
		if (shipment && event && event.eventType?.eventCode != EventCode.CREATED) {   // not allowed to delete a "created" event
			shipment.removeFromEvents(event).save();
			event.delete();
			flash.message = "${warehouse.message(code: 'shipping.deletedEventFromShipment.message', arg=[params.id])}"
		}
		else {
			flash.message = "${warehouse.message(code: 'shipping.couldNotRemoveEventFromShipment.message', arg=[params.id])}"
		}
		redirect(action: 'showDetails', id: params.shipmentId)
	}
	
	def deleteContainer = {
		def container = Container.get(params.id);
		def shipment = Shipment.get(params.shipmentId);
		
		if (shipment && container) {
			container.delete();
			//shipment.removeFromContainers(container).save(flush:true);
			flash.message = "${warehouse.message(code: 'shipping.deletedContainerFromShipment.message', arg=[params.id])}"
		}
		else {
			flash.message = "${warehouse.message(code: 'shipping.couldNotRemoveContainerFromShipment.message', arg=[params.id])}"
		}
		
		redirect(action: 'showDetails', id: params.shipmentId)
	}
	
	def deleteItem = {
		def shipmentItem = ShipmentItem.get(params.id);
		def container = shipmentItem.getContainer();
		def shipmentId = container.getShipment().getId();
		if (item) {
			container.removeFromShipmentItems(shipmentItem)
			//item.delete();
			flash.message = "${warehouse.message(code: 'shipping.deletedShipmentItemFromContainer.message', arg=[params.id,container.name])}"
			redirect(action: 'showDetails', id: shipmentId)
		}
		else {
			flash.message = "${warehouse.message(code: 'shipping.couldNotRemoveItemFromContainer.message', arg=[params.id])}"
			redirect(action: 'showDetails', id: shipmentId)
		}
	}
	
	def deleteComment = {
		def comment = Comment.get(params.id);
		def shipment = Shipment.get(params.shipmentId);
		if (shipment && comment) {
			shipment.removeFromComments(comment).save(flush:true);
			comment.delete();
			flash.message = "${warehouse.message(code: 'shipping.deletedCommentFromShipment.message', arg=[comment4,params.shipmentId])}"
			redirect(action: 'showDetails', id: params.shipmentId)
		}
		else {
			flash.message = "${warehouse.message(code: 'shipping.couldNotRemoveCommentFromShipment.message', arg=[params.id])}"
			redirect(action: 'showDetails', id: params.shipmentId)
		}
	}
	
	
	def editEvent = {
		def eventInstance = Event.get(params.id)
		def shipmentInstance = Shipment.get(params.shipmentId)
		
		if (!eventInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipmentEvent.label', default: 'ShipmentEvent'), params.id])}"
			redirect(action: "showDetails", id: params.shipmentId)
		}
		
		render(view: "editEvent", model: [shipmentInstance : shipmentInstance, eventInstance : eventInstance]);
	}
	
	
	def addEvent = { 
		def shipmentInstance = Shipment.get(params.id);
		
		if (!shipmentInstance) {
			flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipmentEvent.label', default: 'ShipmentEvent'), params.id])}"
			redirect(action: "list")
		}
		
		//def event = new Event(eventType:EventType.get(params.eventType.id), eventDate:new Date())
		def eventInstance = new Event(params);			
		render(view: "editEvent", model: [shipmentInstance : shipmentInstance, eventInstance : eventInstance]);
	}
	
	def saveEvent = {				
		def shipmentInstance = Shipment.get(params.shipmentId);
		def eventInstance = Event.get(params.eventId) ?: new Event()	
		
		bindData(eventInstance, params)

		// check for errors
		if (eventInstance.hasErrors()) {
			flash.message = "${warehouse.message(code: 'shipping.unableToEditEvent.message', arg=[format.metadata(obj:eventInstance?.eventType)])}"
			eventInstance?.errors.allErrors.each { 
				log.error it
			}
			render(view: "editEvent", model: [shipmentInstance : shipmentInstance, eventInstance : eventInstance])
		}
		
		// save (or add) the event
		if (params.eventId) {
			eventInstance.save(flush:true)
		}
		else {
			shipmentInstance.addToEvents(eventInstance).save(flush:true)
		}
		
		redirect(action: 'showDetails', id: shipmentInstance.id)
	}    
	
	def addShipmentItem = { 
		log.info "parameters: " + params
		
		[shipmentInstance : Shipment.get(params.id), 
		containerInstance : Container.get(params?.containerId),
		itemInstance : new ShipmentItem() ]
	}
	
	def addReferenceNumber = { 		
		def referenceNumber = new ReferenceNumber(params);
		def shipment = Shipment.get(params.shipmentId);
		shipment.addToReferenceNumbers(referenceNumber);
		flash.message = "${warehouse.message(code: 'shipping.addedReferenceNumber.message')}"
		redirect(action: 'show', id: params.shipmentId)
	}
	
	def form = {
		[ shipments : Shipment.list() ]
	}
	
	def view = {
		// pass through to "view shipment" page
	}
	
	def generateDocuments = {
		def shipmentInstance = Shipment.get(params.id)
		def shipmentWorkflow = shipmentService.getShipmentWorkflow(shipmentInstance)
		
		if (shipmentWorkflow.documentTemplate) {
			render (view: "templates/$shipmentWorkflow.documentTemplate", model: [shipmentInstance : shipmentInstance])
		}
		else {
			// just go back to the show details page if there is no templaet associated with this shipment workflow
			redirect(action: "showDetails", params : [ 'id':shipmentInstance.id ])
		}
	}
	
	Person convertStringToPerson(String name) { 
		def person = new Person();
		if (name) {
			def nameArray = name.split(" ");
			nameArray.each { 
				if (it.contains("@")) { 
					person.email = it;
				}
				else if (!person.firstName) { 
					person.firstName = it;
				}
				else if (!person.lastName) { 
					person.lastName = it;
				}
				else { 
					person.lastName += " " + it;
				}
			}
			/*
			 if (nameArray.length == 3) {
			 recipient = new Person(firstName : nameArray[0], lastName : nameArray[1], email : nameArray[2]);
			 } else if (nameArray.length == 2) {
			 recipient = new Person(firstName : nameArray[0], lastName : nameArray[1]);
			 } else if (nameArray.length == 1) {
			 recipient = new Person(firstName : nameArray[0]);
			 }*/
		}
		return person;
	}
	
	
	
	
	def addToShipment = { 
		
		// Get product IDs and convert them to Long
		def productIds = params.list('productId')		
		productIds = productIds.collect { Long.valueOf(it); } 
		
		// Find all inventory items that match the selected products
		def inventoryItems = [] 
		if (productIds) { 
			inventoryItems = InventoryItem.findAll("from InventoryItem as i where i.product.id in (:ids)", [ids:productIds])
		}

		// Get quantities for all inventory items
		def warehouse = Warehouse.get(session.warehouse.id)		
		log.info("Quantity for warehouse: " + warehouse.name + " [" + warehouse.inventory + "]")
		def quantityOnHandMap = inventoryService.getQuantityForInventory(warehouse.inventory)
		def quantityShippingMap = shipmentService.getQuantityForShipping(warehouse)
		def quantityReceivingMap = shipmentService.getQuantityForReceiving(warehouse)
		
				
		// Create command objects for each item
		def commandInstance = new ItemListCommand();
		if (inventoryItems) { 
			inventoryItems.each { inventoryItem ->
				def quantityOnHand = quantityOnHandMap[inventoryItem]
				
				def item = new ItemCommand();
				item.quantityOnHand = quantityOnHand
				item.quantityShipping = quantityShippingMap[inventoryItem]
				item.quantityReceiving = quantityReceivingMap[inventoryItem]
				item.inventoryItem = inventoryItem 
				item.product = inventoryItem?.product
				item.lotNumber = inventoryItem?.lotNumber
				commandInstance.items << item;
			}
		}

		// Get all pending/outgoing shipments						
		def shipments = shipmentService.getPendingShipments(warehouse);
		
		[shipments : shipments, commandInstance : commandInstance]
	}
	
	
	def addToShipmentPost = { ItemListCommand command -> 
		log.info(params);
		log.info("Command items " + command?.class?.name + " " + command?.items?.size());
		try { 
			boolean atLeastOneUpdate = shipmentService.addToShipment(command);
			if (atLeastOneUpdate) { 
				flash.message = "${warehouse.message(code: 'shipping.shipmentItemsHaveBeenAdded.message')}"
			}
			else { 
				flash.message = "${warehouse.message(code: 'shipping.noShipmentItemsHaveBeenUpdated.message')}"
			}
		} catch (ShipmentItemException e) { 
			flash['errors'] = e.shipmentItem.errors
			redirect(action: "addToShipment", params:params)
			return;
		} catch (ValidationException e) { 			
			flash['errors'] = e.errors 
			redirect(action: "addToShipment", params:params)
			return;
		}
		
		redirect(controller: "inventory", action: "browse")	
		
	}
	
	
}





