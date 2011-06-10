#!/usr/bin/env python2.7

# To debug this, python -m pdb myscript.py

#import pwd
import os, csv, json, re, string, datetime, urllib, httplib, ConfigParser, itertools, argparse, codecs, cStringIO, synapse.client, synapse.utils

#-------[ Documentation embedded in Command Line Arguments ]----------------
parser = synapse.utils.createBasicArgParser('Tool to load metadata into a Sage Platform Repository Service.  Note that this script always create instance of a dataset in the repository service (the repository service does not enforce uniqueness of dataset names).  Use the datasetNuker.py script first if you want to start with a clean datastore.')

synapse.client.addArguments(parser)

parser.add_argument('--datasetsCsv', '-d', help='the file path to the CSV file holding dataset metadata, defaults to AllDatasets.csv', default='AllDatasets.csv')

parser.add_argument('--layersCsv', '-l', help='the file path to the CSV file holding layer metadata, defaults to AllDatasetLayerLocations.csv', default='AllDatasetLayerLocations.csv')

parser.add_argument('--md5sumCsv', '-m', help='the file path to the CSV file holding the md5sums for files, defaults to ../platform.md5sums.csv', default='../platform.md5sums.csv')

parser.add_argument('--fakeLocalData', '-f', help='use fake data when we would normally read something from the actual Sage Bionetworks datasets, defaults to False', action='store_true', default=False)

#-------------------[ Constants ]----------------------

NOW = datetime.datetime.today()

# These are the fields in the CSV that correspond to primary fields in
# our data model, all other fields in the CSV will be tossed into the
# annotation buckets
CSV_TO_PRIMARY_FIELDS = {
    'name': 'name',
    'description': 'description',
    'Investigator': 'creator',
    'Creation Date': 'creationDate',
    'Status': 'status',
    'date_released': 'releaseDate',
    'version':'version'
    }

CSV_SKIP_FIELDS = ["db_id","user_agreement_file_path", "readme_file_path"];

gSAGE_CURATION_PROJECT_NAME = "SageBioCuration"

#-------------------[ Global Variables ]----------------------

# Command line arguments
gARGS = {}
gARGS = parser.parse_args()
gSYNAPSE = synapse.client.factory(gARGS)

# A mapping we build over time of dataset names to layer uris.  In our
# layer CSV file we have the dataset name to which each layer belongs.
gDATASET_NAME_2_LAYER_URI = {}

# A mapping of files to their md5sums
gFILE_PATH_2_MD5SUM = {}

def createAccessList(principals):
    """
    Helper function to return access list from list of principals
    """
    # TODO: Should find a nicer way to specify permissions (i.e. read from config file)
    perms = {
        "Sage Curators":["READ","CHANGE_PERMISSIONS","DELETE","UPDATE","CREATE"],
        "Identified Users":["READ"]
    }
    al = []
    for p in principals:
        if p["name"] in perms:
            al.append({"userGroupId":p["id"], "accessType":perms[p["name"]]})
    return al

#--------------------[ createProject ]-----------------------------
def createProject(project_name, accessList):
    # TODO: pass project spec as arg
    projectSpec = {"name":project_name, "description":"Umbrella for Sage-curated projects","creationDate":"2011-06-06", "creator":"x.schildwachter@sagebase.org"}
    project = gSYNAPSE.createProject(projectSpec)
    projectResourceId = project["id"]

    # Build list of changes to ACL
    mods = {"modifiedBy":"dataLoader", "modifiedOn":NOW.__str__().split(' ')[0], "resourceAccess":accessList}
    
    # For non-inherited ACL, GET and PUT instead of POST
    gSYNAPSE.updateRepoEntity("/project/" + projectResourceId + "/acl", mods)
    return project


#--------------------[ createDataset ]-----------------------------
def createDataset(dataset, annotations):
    newDataset = gSYNAPSE.createRepoEntity("/dataset", dataset)
    # Put our annotations
    gSYNAPSE.updateRepoEntity(newDataset["annotations"], annotations)
    # Stash the layer uri for later use
    gDATASET_NAME_2_LAYER_URI[dataset['name']] = newDataset['id']
    print 'Created Dataset %s\n\n' % (dataset['name'])
      
#--------------------[ loadMd5sums ]-----------------------------
def loadMd5sums():
    ifile  = open(gARGS.md5sumCsv, "rU")
    for line in ifile:
        row = string.split(line.rstrip())
        md5sum = row[0]
        # strip off any leading forward slashes
        filePath = string.lstrip(row[1], "/")
        gFILE_PATH_2_MD5SUM[filePath] = md5sum
        
#--------------------[ loadDatasets ]-----------------------------
# What follows is code that expects a dataset CSV in a particular format,
# sorry its so brittle and ugly
def loadDatasets(project_id):
    # xschildw: Use codecs.open and UnicodeReader class to handle extended chars
    ifile  = open(gARGS.datasetsCsv, "r")
    reader = synapse.utils.UnicodeReader(ifile, encoding='latin_1')

    # loop variables
    rownum = -1
    previousDatasetId = None;
    # per dataset variables
    colnum = 0
    dataset = {}
    annotations = {}
    stringAnnotations = {}
    doubleAnnotations = {}
    longAnnotations = {}
    dateAnnotations = {}
    annotations['stringAnnotations'] = stringAnnotations
    annotations['doubleAnnotations'] = doubleAnnotations
    annotations['longAnnotations'] = longAnnotations
    annotations['dateAnnotations'] = dateAnnotations
    stringAnnotations['Tissue_Tumor'] = []
    
    for row in reader:
        rownum += 1

        # Save header row
        if rownum == 0:
            header = row
            colnum = 0
            for col in row:
                # Replace all runs of whitespace with a single dash
                header[colnum] = re.sub(r"\s+", '_', col)
                colnum += 1
            continue

        # Bootstrap our previousDatasetId
        if(None == previousDatasetId):
            previousDatasetId = row[0]
    
        # If we have read in all the data for a dataset, send it
        if(previousDatasetId != row[0]):
            # Create our dataset
            createDataset(dataset, annotations)
            # Re-initialize per dataset variables
            previousDatasetId = row[0]
            dataset = {}
            annotations = {}
            stringAnnotations = {}
            doubleAnnotations = {}
            longAnnotations = {}
            dateAnnotations = {}
            annotations['stringAnnotations'] = stringAnnotations
            annotations['doubleAnnotations'] = doubleAnnotations
            annotations['longAnnotations'] = longAnnotations
            annotations['dateAnnotations'] = dateAnnotations
            stringAnnotations['Tissue_Tumor'] = []
                        
        # Load the row data from the dataset CSV into our datastructure    
        colnum = 0
        for col in row:
            if(gARGS.debug):
                print '%-8s: %s' % (header[colnum], col)
            if(header[colnum] in CSV_TO_PRIMARY_FIELDS):
                if("name" == header[colnum]):
                    cleanName = col.replace("_", " ")
                    dataset[CSV_TO_PRIMARY_FIELDS[header[colnum]]] = cleanName
                else:
                    dataset[CSV_TO_PRIMARY_FIELDS[header[colnum]]] = col
            elif(header[colnum] in CSV_SKIP_FIELDS):
                if(gARGS.debug):
                    print 'SKIPPING %-8s: %s' % (header[colnum], col)
                # TODO consider reading these into fields
                #             user_agreement_file_path
                #             readme_file_path
            else:
                if( re.search('date', string.lower(header[colnum])) ):
                    dateAnnotations[header[colnum]] = [col]
                else:
                    try:
                        value = float(col)
                        if(value.is_integer()):
                            longAnnotations[header[colnum]] = [value]
                        else:
                            doubleAnnotations[header[colnum]] = [value]
                    except (AttributeError, ValueError):
                        # Note that all values in the spreadsheet from the
                        # mysql db are single values except for this one
                        if("Tissue/Tumor" == header[colnum]): 
                            stringAnnotations['Tissue_Tumor'].append(col)
                        else:
                            stringAnnotations[header[colnum]] = [col]
            colnum += 1
        dataset["parentId"] = project_id
    ifile.close()     

    # Send the last one, create our dataset
    createDataset(dataset, annotations)

#--------------------[ loadLayers ]-----------------------------
def loadLayers():
    # What follows is code that expects a layerCsv in a particular format,
    # sorry its so brittle and ugly
    ifile  = open(gARGS.layersCsv, "r")
    reader = synapse.utils.UnicodeReader(ifile, encoding='latin_1')
    rownum = -1
    for row in reader:
        rownum += 1

        if rownum == 0:
            # Save header row
            header = row
            continue
        
        # xschildw: new format is
        # Dataset Name,type,status,name,Number of samples,Platform,Version,preview,sage,awsebs,awss3,qcby
        colnum = 0
        layerUri = "/layer"
        layer = {}
        layer["parentId"] = gDATASET_NAME_2_LAYER_URI[row[0]]
        layer["type"] = row[1]
        layer["status"] = row[2]
        layer["name"] = row[3]
        layer["numSamples"] = row[4]
        layer["platform"] = row[5]
        layer["version"] = row[6]
        layer["qcBy"] = row[11]
        
        newLayer = gSYNAPSE.createRepoEntity(layerUri, layer)
        print 'Created layer %s for %s\n\n' % (layer["name"], row[0])
        
        # Ignore column 8 (sage loc) and 9 (awsebs loc) for now
        for col in [10]:
            if(row[col] != ""):
                # trim whitespace off both sides
                path = row[col].strip()
                location = {}
                location["parentId"] = newLayer["id"]
                location["type"] = header[col]
                if 0 != string.find(path, "/"):
                    location["path"] = "/" + path
                else:
                    location["path"] = path
                if(path in gFILE_PATH_2_MD5SUM):
                    location["md5sum"] = gFILE_PATH_2_MD5SUM[path]
                elif(gARGS.fakeLocalData):
                    location["md5sum"] = '0123456789ABCDEF0123456789ABCDEF'
                gSYNAPSE.createRepoEntity("/location", location)
                #print 'Created location %s for %s\n' % (layer["type"], row[0])
        
        layerPreview = {}
        
        if(row[7] != ""):
            layerPreview["parentId"] = newLayer["id"]
            if(gARGS.fakeLocalData):
                layerPreview["previewString"] = 'this\tis\ta\tfake\tpreview\nthis\tis\ta\tfake\tpreview\n'
            else:
                with open(row[7]) as myfile:
                    # Slurp in the first six lines of the file and store
                    # it in our property
                    head = ""
                    layerPreview["previewString"] = head.join(itertools.islice(myfile, 6))
            gSYNAPSE.createRepoEntity("/preview", layerPreview)
       
    ifile.close()     

#--------------------[ Main ]-----------------------------

if(not gARGS.fakeLocalData):
    loadMd5sums()

gSYNAPSE.authenticate(gARGS.user, gARGS.password)
gSYNAPSE.getRepoEntity('/dataset')          # Dummy call to load up groups

principals = gSYNAPSE.getPrincipals()
accessList = createAccessList(principals)

project = createProject(gSAGE_CURATION_PROJECT_NAME, accessList)

loadDatasets(project["id"])

if(None != gARGS.layersCsv):
    loadLayers()

