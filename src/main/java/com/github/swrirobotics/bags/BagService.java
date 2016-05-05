// *****************************************************************************
//
// Copyright (c) 2015, Southwest Research Institute® (SwRI®)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//     * Redistributions of source code must retain the above copyright
//       notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above copyright
//       notice, this list of conditions and the following disclaimer in the
//       documentation and/or other materials provided with the distribution.
//     * Neither the name of Southwest Research Institute® (SwRI®) nor the
//       names of its contributors may be used to endorse or promote products
//       derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL Southwest Research Institute® BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
// OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
// DAMAGE.
//
// *****************************************************************************

package com.github.swrirobotics.bags;

import com.github.swrirobotics.bags.persistence.*;
import com.github.swrirobotics.bags.reader.BagFile;
import com.github.swrirobotics.bags.reader.BagReader;
import com.github.swrirobotics.bags.reader.TopicInfo;
import com.github.swrirobotics.bags.reader.exceptions.BagReaderException;
import com.github.swrirobotics.remote.GeocodingService;
import com.github.swrirobotics.status.Status;
import com.github.swrirobotics.status.StatusProvider;
import com.github.swrirobotics.support.web.BagList;
import com.github.swrirobotics.support.web.ExtJsFilter;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.lucene.search.Query;
import org.hibernate.search.jpa.FullTextEntityManager;
import org.hibernate.search.jpa.FullTextQuery;
import org.hibernate.search.jpa.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.*;
import java.io.File;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BagService extends StatusProvider {
    @Autowired
    private BagRepository bagRepository;
    @Autowired
    private BagPositionRepository myBagPositionRepository;
    @Autowired
    private MessageTypeRepository myMTRepository;
    @Autowired
    private TopicRepository myTopicRepository;
    @Autowired
    private GeocodingService myGeocodingService;
    @PersistenceContext
    private EntityManager myEM;

    final private Object myBagDbLock = new Object();

    private Logger myLogger = LoggerFactory.getLogger(BagService.class);

    @Transactional(readOnly = true)
    public Bag getBag(Long bagId) {
        try {
            Bag response = bagRepository.findOne(bagId);
            myLogger.debug("Successfully got bag: " + response.getFilename());
            return response;
        }
        catch (RuntimeException e) {
            myLogger.error("Unable to get bag:", e);
            throw e;
        }

    }

    public void updateIndexes() {
        String msg = "Rebuilding Lucene search indices.";
        reportStatus(Status.State.WORKING, msg);
        myLogger.info(msg);
        try {
            myLogger.trace("Initializing FullTextEntityManager.");
            FullTextEntityManager ftem = Search.getFullTextEntityManager(myEM);
            myLogger.info("Performing initial database indexing.");
            ftem.createIndexer().startAndWait();
            ftem.flushToIndexes();
            myLogger.info("Done indexing.");
            reportStatus(Status.State.IDLE, "Done rebuilding search indices.");
        }
        catch (InterruptedException e) {
            String error = "Interrupted while rebuilding search indices.";
            myLogger.error(error, e);
            reportStatus(Status.State.ERROR, error);
        }
        catch (RuntimeException e) {
            String error = "Unexpected exception while rebuilding search indices: " + e.getLocalizedMessage();
            myLogger.error(error, e);
            reportStatus(Status.State.ERROR, error);
        }
        reportStatus(Status.State.IDLE, "Done rebuilding database indices.");
    }

    @Transactional
    public void removeDuplicateBags() {
        String msg = "Removing duplicate bag files.";
        myLogger.info(msg);
        reportStatus(Status.State.WORKING, msg);
        List<Bag> bags = bagRepository.findAll();
        Map<String, List<Bag>> md5Bags = Maps.newHashMap();

        for (Bag bag : bags) {
            List<Bag> tmp = md5Bags.get(bag.getMd5sum());
            if (tmp == null) {
                tmp = Lists.newArrayList();
                md5Bags.put(bag.getMd5sum(), tmp);
            }
            tmp.add(bag);
        }

        myLogger.info("Found " + bags.size() + " bags with " +
                      md5Bags.keySet().size() + " different MD5s.");

        for (List<Bag> sublist : md5Bags.values()) {
            if (sublist.size() > 1) {
                myLogger.debug("Found " + sublist.size() +
                    " duplicates for MD5 sum " + sublist.get(0).getMd5sum() + ".");
                for (int i = 1; i < sublist.size(); i++) {
                    Bag dupBag = sublist.get(i);
                    msg = "Removing bag w/ ID " + dupBag.getMd5sum();
                    myLogger.debug(msg);
                    reportStatus(Status.State.WORKING, msg);
                    bagRepository.delete(dupBag);
                }
            }
        }
        msg = "Done removing duplicates.";
        myLogger.info(msg);
        reportStatus(Status.State.IDLE, msg);
    }

    @Transactional
    public void updateBag(Bag newBag) {
        Bag dbBag = bagRepository.findOne(newBag.getId());
        dbBag.setDescription(newBag.getDescription());
        dbBag.setLatitudeDeg(newBag.getLatitudeDeg());
        dbBag.setLongitudeDeg(newBag.getLongitudeDeg());
        dbBag.setLocation(newBag.getLocation());
        dbBag.setVehicle(newBag.getVehicle());
        dbBag.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
        bagRepository.save(dbBag);
    }

    @Transactional(readOnly = true)
    public List<Bag> searchText(String text) {
        // This uses the indexes generated by Lucene to search, which in practice
        // seems much slower than just building a SQL specification.  Maybe revisit
        // this later.
        Set<Bag> bagSet = Sets.newHashSet();
        FullTextEntityManager ftem = Search.getFullTextEntityManager(myEM);
        org.hibernate.search.query.dsl.QueryBuilder qb =
                ftem.getSearchFactory().buildQueryBuilder().forEntity(Bag.class).get();


        Query lQuery = qb.keyword().wildcard().onFields(
                "filename", "messageTypes.mt_name", "messageTypes.mt_md5sum",
                "topics.topic_name").matching(text).createQuery();
        bagSet.addAll(runQuery(ftem, lQuery));

        lQuery = qb.phrase().onField("filename").andField("path").sentence(text).createQuery();
        bagSet.addAll(runQuery(ftem, lQuery));

        List<Bag> resultList = Lists.newArrayList(bagSet);

        return resultList;
    }

    private Pageable createPageRequest(int page, int size, String dir, String sort) {
        // ExtJS starts counting pages at 1, but Spring Data JPA starts counting at 0.
        return new PageRequest(page-1,
                               size,
                               dir.equalsIgnoreCase("ASC") ? Sort.Direction.ASC : Sort.Direction.DESC,
                               sort);
    }

    private <X> Predicate fromFilter(ExtJsFilter filter, CriteriaBuilder cb, Path<X> path) {
        Timestamp ts = null;
        switch (filter.getProperty()) {
            case "startTime":
            case "endTime":
            case "updatedOn":
            case "createdOn":
                ts = new Timestamp(Long.valueOf(filter.getValue()));
                break;
        }

        Path propertyPath = path.get(filter.getProperty());

        Predicate pred = null;

        switch (filter.getOperator()) {
            case "like":
                pred = cb.like(cb.lower(propertyPath), "%" + filter.getValue().toLowerCase() + "%");
                break;
            case "lt":
                if (ts != null) {
                    pred = cb.lessThan(propertyPath, ts);
                }
                else {
                    pred = cb.lessThan(propertyPath, Long.valueOf(filter.getValue()));
                }
                break;
            case "gt":
                if (ts != null) {
                    pred = cb.greaterThan(propertyPath, ts);
                }
                else {
                    pred = cb.greaterThan(propertyPath, Long.valueOf(filter.getValue()));
                }
                break;
            case "eq":
                if (ts != null) {
                    pred = cb.equal(propertyPath, ts);
                }
                else {
                    pred = cb.equal(path.get(filter.getProperty()), Long.valueOf(filter.getValue()));
                }
                break;
            case "=":
                pred = cb.equal(path.get(filter.getProperty()), filter.getValue().equals("true"));
                break;
            default:
                break;
        }

        return pred;
    }

    private Predicate fullTextPredicate(final String text,
                                        final String[] fields,
                                        CriteriaBuilder cb,
                                        Root<Bag> root) {
        final String wildcardText = "%" + text.toLowerCase() + "%";
        // We'll be searching through the text fields in all of the related tables
        // Fields that currently aren't being searched: tags, md5sum
        // Tags because they don't actually exist yet
        // md5sum because nobody really cares about that
        //Join<Bag, Tag> tagJoin = root.join(Bag_.tags, JoinType.LEFT);

        List<Predicate> preds = Lists.newArrayList();
        for (String field : fields) {
            switch(field) {
                case "messageType":
                    Join<Bag, MessageType> mtJoin = root.join(Bag_.messageTypes, JoinType.LEFT);
                    preds.add(cb.like(cb.lower(mtJoin.get(MessageType_.name)), wildcardText));
                    break;
                case "topicName":
                    Join<Bag, Topic> topicJoin = root.join(Bag_.topics, JoinType.LEFT);
                    preds.add(cb.like(cb.lower(topicJoin.get(Topic_.topicName)), wildcardText));
                    break;
                default:
                    preds.add(cb.like(cb.lower(root.get(field)), wildcardText));
                    break;
            }
        }

        return cb.or(preds.toArray(new Predicate[preds.size()]));
    }

    @Transactional(readOnly = true)
    public BagList findBagsContainingText(final String text,
                                          final String[] fields,
                                          final ExtJsFilter[] filters,
                                          int page,
                                          int size,
                                          String dir,
                                          String sort) {
        myLogger.trace("Executing specification.");

        Page<Bag> bags;
        Pageable pageReq = createPageRequest(page, size, dir, sort);

        if ((text == null || text.trim().isEmpty() || fields == null || fields.length == 0) &&
            (filters == null || filters.length == 0)) {
            bags = bagRepository.findAll(pageReq);
        }
        else {
            bags = bagRepository.findAll((root, query, cb) -> {
                // We only want one result per bag file, though.
                query.distinct(true);

                List<Predicate> preds = Lists.newArrayList();
                if (text != null && !text.trim().isEmpty() &&
                    fields != null && fields.length != 0) {
                    preds.add(fullTextPredicate(text, fields, cb, root));
                }
                if (filters != null && filters.length > 0) {
                    for (ExtJsFilter filter : filters) {
                        preds.add(fromFilter(filter, cb, root));
                    }
                }

                if (preds.size() == 1) {
                    return preds.get(0);
                }
                else {
                    return cb.and(preds.toArray(new Predicate[preds.size()]));
                }
            }, pageReq);
        }
        myLogger.trace("Finished executing.");

        return new BagList(bags.getContent(), bags.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<Double[]> getGpsCoordsForBags(final Collection<Long> bagIds) {
        myLogger.debug("getGpsCoordsForBags: " + Joiner.on(", ").skipNulls().join(bagIds));

        List<BagPosition> positions =
                myBagPositionRepository.findByBagIdInOrderByPositionTimeAsc(bagIds);

        List<Double[]> coords = positions.parallelStream().map(p -> new Double[] {
                p.getLongitude(), p.getLatitude()}).collect(Collectors.toList());

        myLogger.debug("Returning " + coords.size() + " points.");

        return coords;
    }

    @Transactional(readOnly = true)
    public List<Long> getAllBagIds() {
        List<Bag> bags = bagRepository.findAll();
        List<Long> bagIds = Lists.newArrayListWithCapacity(bags.size());

        bagIds.addAll(bags.parallelStream().map(Bag::getId).collect(Collectors.toList()));

        return bagIds;
    }

    @Transactional
    public void updateGpsPositionsForBagId(long bagId) {
        Bag bag = bagRepository.findOne(bagId);
        String fullPath = bag.getPath() + bag.getFilename();
        try {
            BagFile bagFile = BagReader.readFile(fullPath);
            updateGpsPositions(bag, bagFile.getAllGpsMessages());
            bagRepository.save(bag);
        }
        catch (BagReaderException e) {
            reportStatus(Status.State.ERROR,
                         "Unable to get GPS info for " + fullPath + ": " + e.getLocalizedMessage());
        }
    }

    @Transactional
    public void updateGpsPositions(final Bag bag, final BagFile.GpsPositions gpsPositions) throws BagReaderException {
        List<BagPosition> existingPositions = bag.getBagPositions();
        if (!existingPositions.isEmpty()) {
            myLogger.warn("Adding new GPS positions for a bag that already has " +
                                  "some in the database is not supported.  " +
                                  "Manually remove the old ones first.");
            bag.setHasPath(true);
            return;
        }

        String msg = "Inserting GPS positions for " + bag.getFilename() + ".";
        myLogger.debug(msg);
        reportStatus(Status.State.WORKING, msg);
        bag.setHasPath(!gpsPositions.positions.isEmpty());
        for (int i = 0; i < gpsPositions.positions.size(); i++) {
            BagPosition pos = new BagPosition();
            pos.setBag(bag);
            pos.setLongitude(gpsPositions.positions.get(i)[0]);
            pos.setLatitude(gpsPositions.positions.get(i)[1]);
            pos.setPositionTime(gpsPositions.timestamps.get(i));
            pos = myBagPositionRepository.save(pos);
            bag.getBagPositions().add(pos);
        }
        msg = "Saved " + gpsPositions.positions.size() + " GPS positions for " +
                bag.getFilename() + ".";
        myLogger.trace(msg);
        reportStatus(Status.State.IDLE, msg);
    }

    private List<Bag> runQuery(FullTextEntityManager ftem, Query query) {
        FullTextQuery ftQuery = ftem.createFullTextQuery(query, Bag.class);
        myLogger.info("Running query.");
        List queryResults = ftQuery.getResultList();
        myLogger.info("result count: " + queryResults.size());
        List<Bag> results = Lists.newArrayListWithExpectedSize(queryResults.size());
        for (Object obj: queryResults) {
            results.add((Bag)obj);
        }

        return results;
    }

    @Transactional
    public void scanDatabaseBags(Map<String, Long> existingBagPaths,
                                  Map<String, Long> missingBagMd5sums) {
        List<Bag> bags = bagRepository.findAll();

        // First, scan over all of the existing entries in the DB and see if
        // any of them are missing from the filesystem.
        myLogger.debug("Scanning bags already in the database.");
        for (Bag bag : bags) {
            String fullPath = bag.getPath() + bag.getFilename();
            myLogger.trace("Checking whether " + fullPath + "...");
            File testFile = new File(fullPath);
            if (testFile.exists()) {
                existingBagPaths.put(fullPath, bag.getId());
                if (bag.getMissing()) {
                    bag.setMissing(false);
                    bagRepository.save(bag);
                }
            }
            else {
                myLogger.warn("Bag exists in database but is missing: " + fullPath);
                missingBagMd5sums.put(bag.getMd5sum(), bag.getId());
                if (!bag.getMissing()) {
                    bag.setMissing(true);
                    bagRepository.save(bag);
                }
            }
        }
    }

    @Transactional
    public Bag insertNewBag(final BagFile bagFile,
                            final String md5sum,
                            final String locationName,
                            final BagFile.GpsPositions gpsPositions) throws BagReaderException, DuplicateBagException {
        Bag bag = bagRepository.findByMd5sum(md5sum);

        // We checked earlier if there were any other bags with this MD5 sum,
        // but that was before we entered the synchronized area, so we need
        // to check again just in case somebody managed to insert one before we
        // got the lock.
        if (bag != null) {
            throw new DuplicateBagException("Duplicate of: " + bag.getPath() + bag.getFilename());
        }

        bag = new Bag();

        File file = bagFile.getPath().toFile();
        String path = file.getPath().replace(file.getName(), "");

        myLogger.info("Adding new bag: " + file.getPath());
        // If it doesn't exist in the DB, create a new entry.
        bag.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        bag.setPath(path);
        bag.setFilename(file.getName());
        bag.setMd5sum(md5sum);
        bag.setCompressed(false);
        bag.setDuration(bagFile.getDurationS());
        bag.setStartTime(bagFile.getStartTime());
        bag.setEndTime(bagFile.getEndTime());
        bag.setIndexed(bagFile.isIndexed());
        bag.setMessageCount(bagFile.getMessageCount());
        bag.setMissing(false);
        bag.setSize(file.length());
        bag.setVersion(bagFile.getVersion());
        bag.setVehicle(bagFile.getVehicleName());
        if (!gpsPositions.positions.isEmpty()) {
            Double[] firstPos = gpsPositions.positions.get(0);
            bag.setLatitudeDeg(firstPos[1]);
            bag.setLongitudeDeg(firstPos[0]);
        }
        bag.setLocation(locationName);
        bag = bagRepository.save(bag);
        myLogger.trace("Initial bag save for " + file.getAbsolutePath());

        Map<String, MessageType> dbMessageTypes = addMessageTypesToBag(bagFile, bag);

        addTopicsToBag(bagFile, bag, dbMessageTypes);

        updateGpsPositions(bag, gpsPositions);

        return bag;
    }

    @Transactional
    private Map<String, MessageType> addMessageTypesToBag(final BagFile bagFile, final Bag bag) {
        myLogger.trace("Adding message types.");
        Multimap<String, String> messageTypes = bagFile.getMessageTypes();
        Map<String, MessageType> dbMessageTypes = new HashMap<>();
        for (Map.Entry<String, String> entry : messageTypes.entries()) {
            MessageType mt = getMessageType(entry.getKey(), entry.getValue(), bag);
            dbMessageTypes.put(entry.getKey(), mt);
        }

        return dbMessageTypes;
    }

    @Transactional
    private void addTopicsToBag(final BagFile bagFile,
                                final Bag bag,
                                final Map<String, MessageType> dbMessageTypes) throws BagReaderException {
        myLogger.trace("Adding topics.");
        List<TopicInfo> topics = bagFile.getTopics();
        for (TopicInfo topic : topics) {
            MessageType dbType = dbMessageTypes.get(topic.getMessageType());
            if (dbType == null) {
                myLogger.trace("Need to add new message type. That's a little odd, " +
                               "addMessageTypesToBag should've gotten them all.");
                dbType = getMessageType(
                        topic.getMessageType(),
                        topic.getMessageMd5Sum(),
                        bag);
                dbMessageTypes.put(topic.getMessageType(), dbType);
            }
            else {
                myLogger.trace("Found cached message type.");
            }

            myLogger.trace("Finding existing topics.");
            List<Topic> bagTopics = myTopicRepository.findByTopicNameAndBagId(topic.getName(), bag.getId());
            //myLogger.info("Found " + bagTopics + " existing topics.");
            Topic dbTopic;
            if (!bagTopics.isEmpty()) {
                dbTopic = bagTopics.get(0);
            }
            else {
                //myLogger.info("Creating new topic.");
                dbTopic = new Topic();
            }
            dbTopic.setTopicName(topic.getName());
            dbTopic.setType(dbType);
            dbTopic.setMessageCount(topic.getMessageCount());
            dbTopic.setConnectionCount(topic.getConnectionCount());
            dbTopic.setBag(bag);
            if (!bag.getTopics().contains(dbTopic)) {
                bag.getTopics().add(dbTopic);
            }
        }
    }

    public void updateBagFile(final File file,
                              final Map<String, Long> existingBagPaths,
                              final Map<String, Long> missingBagMd5sums,
                              boolean forceUpdate) {
        myLogger.debug("Checking " + file.getPath() + "...");
        reportStatus(Status.State.WORKING, "Processing " + file.getPath() + ".");

        Long bagId = existingBagPaths.get(file.getPath());
        // If it already exists in the database, don't do anything unless this
        // is a force update.
        if (bagId != null) {
            if (forceUpdate) {
                myLogger.debug("Bag already exists in database; update forced.");
            }
            else {
                myLogger.trace("Bag exists in database; skipping.");
                return;
            }
        }

        if (!file.canRead()) {
            myLogger.error("Can't read file.");
            reportStatus(Status.State.ERROR, "Unable to read " + file.getPath() + ".  Check its permissions.");
            return;
        }

        String md5sum;
        Timer timer = new Timer();
        try {
            // First, get the MD5 sum so we can see if this bag exists but
            // has been moved.
            BagFile bagFile = new BagFile(file.getPath());
            //InputStream inputStream = new BufferedInputStream(new FileInputStream(file));

            TimerTask updateTask = new TimerTask() {
                @Override
                public void run() {
                    reportStatus(Status.State.WORKING,
                                 "Calculating MD5 Sum for " + file.getName() + "...");
                }
            };
            // Periodically notify the front end if we're still calculating MD5 sums.
            // Otherwise, if we're analyzing multiple bags in parallel, an error could
            // occur that might make the user think we're not working on anything else.
            timer.scheduleAtFixedRate(updateTask, 0, 3000);

            md5sum = bagFile.getUniqueIdentifier();
            myLogger.debug("Calculated bag md5sum: " + md5sum);
        }
        catch (BagReaderException e) {
            myLogger.error("Unable to calculate MD5 sum for bag " + file.getPath(), e);
            return;
        }
        finally {
            timer.cancel();
        }

        // If bag is null at this point, that means that there is not an existing
        // bag in the database with that path.  There might be a bag that was
        // previously marked as "missing" with that MD5 sum, so check for it.
        if (bagId == null) {
            bagId = missingBagMd5sums.get(md5sum);
        }

        // If it's still null, check whether there's an existing bag in the database
        // with that MD5 sum so we can avoid doing any more work.
        if (bagId == null) {
            Bag existingBag = bagRepository.findByMd5sum(md5sum);
            if (existingBag != null) {
                String msg = "File " + file.getAbsolutePath() + " is a duplicate of " +
                             existingBag.getPath() + existingBag.getFilename() + ".";
                reportStatus(Status.State.ERROR, msg);
                myLogger.warn(msg);
                return;
            }
        }

        // Getting the list of GPS positions is a bit expensive, and getting the location
        // name can block while waiting for a network response, so let's do those
        // before locking on the mutex.
        BagFile bagFile;
        String locationName = null;
        BagFile.GpsPositions gpsPositions;
        try {
            bagFile = BagReader.readFile(file);

            gpsPositions = bagFile.getAllGpsMessages();

            if (!gpsPositions.positions.isEmpty()) {
                Double[] firstPos = gpsPositions.positions.get(0);
                locationName = myGeocodingService.getLocationName(firstPos[1], firstPos[0]);
            }
        }
        catch (BagReaderException e) {
            myLogger.error("Error reading GPS messages from bag file:", e);
            return;
        }

        // We can do the work up to this point in parallel -- mostly calculating
        // md5sums -- but we need to synchronize around DB transactions, since
        // different bags could all try to insert the same types of messages at
        // the same time.
        synchronized (myBagDbLock) {
            try {
                updateBagInDatabase(bagId, bagFile, md5sum, missingBagMd5sums, locationName, gpsPositions);
            }
            catch (BagReaderException | DuplicateBagException e) {
                reportStatus(Status.State.ERROR, "Error reading " +
                             file.getAbsolutePath() + ": " + e.getLocalizedMessage());
                myLogger.error("Error reading bag file: " + file.getAbsolutePath(), e);
            }
        }
    }

    @Transactional
    public void updateBagInDatabase(Long bagId,
                                    final BagFile bagFile,
                                    final String md5sum,
                                    final Map<String, Long> missingBagMd5sums,
                                    final String locationName,
                                    final BagFile.GpsPositions gpsPositions)
            throws DuplicateBagException, BagReaderException {
        Bag bag;
        File file = bagFile.getPath().toFile();
        if (bagId == null) {
            bag = insertNewBag(bagFile, md5sum, locationName, gpsPositions);
        }
        else {
            if (missingBagMd5sums.remove(md5sum) != null) {
                myLogger.info("Missing bag was found.");
            }
            else {
                myLogger.info("Force updating bag info.");
            }
            // If we found a missing one, remove it from the list and update
            // its path.
            String path = file.getPath().replace(file.getName(), "");
            bag = bagRepository.findOne(bagId);
            bag.setPath(path);
            bag.setFilename(file.getName());
            bag.setMissing(false);
            bag.setMd5sum(md5sum);
        }
        bagRepository.save(bag);
        myLogger.debug("Final bag save; done processing " + file.getAbsolutePath());
    }

    @Transactional
    public void markMissingBags(final Collection<Long> missingBags) {
        for (Long bagId : missingBags) {
            Bag bag = bagRepository.findOne(bagId);
            if (!bag.getMissing()) {
                myLogger.warn("Bag " + bag.getPath() + bag.getFilename() +
                                      " was missing and we couldn't find it.");
                bag.setMissing(true);
                bagRepository.save(bag);
            }
        }
    }

    public void removeMissingBags() {
        myLogger.info("removeMissingBags()");
        reportStatus(Status.State.WORKING, "Removing missing bag entries.");
        List<Bag> missingBags = bagRepository.findByMissing(true);
        for (Bag bag : missingBags) {
            try {
                removeBag(bag.getId());
            }
            catch (RuntimeException e) {
                String error = "Error removing bag:";
                reportStatus(Status.State.ERROR, error + e.getMessage());
                myLogger.error(error, e);
            }
        }
        String msg = "Removed " + missingBags.size() + " bags.";
        myLogger.debug(msg);
        reportStatus(Status.State.IDLE, msg);
    }

    @Transactional
    public void removeBag(Long bagId) {
        Bag bag = bagRepository.findOne(bagId);
        bag.getMessageTypes().clear();
        bagRepository.delete(bag);
    }

    private MessageType getMessageType(final String name,
                                       final String md5sum,
                                       final Bag bag) {
        MessageTypeKey key = new MessageTypeKey();
        key.name = name;
        key.md5sum = md5sum;
        MessageType dbType = myMTRepository.findOne(key);
        if (dbType == null) {
            myLogger.info("Adding new MessageType to DB: " +
                                  name + " / " + md5sum);
            dbType = new MessageType();
            dbType.setMd5sum(md5sum);
            dbType.setName(name);
        }
        else {
            myLogger.debug("Found existing MessageType in DB: " +
                                   name + " / " + md5sum);
        }
        if (!bag.getMessageTypes().contains(dbType)) {
            bag.getMessageTypes().add(dbType);
        }
        return dbType;
    }

    @Override
    protected String getStatusProviderName() {
        return "Bag Service";
    }
}