package extractors;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javatools.administrative.Announce;
import javatools.administrative.D;
import javatools.datatypes.FinalSet;
import javatools.filehandlers.FileLines;
import javatools.parsers.Char;
import javatools.util.FileUtils;
import extractorUtils.PatternList;
import extractorUtils.TermExtractor;
import extractorUtils.TitleExtractor;

import basics.Fact;
import basics.FactCollection;
import basics.FactComponent;
import basics.FactSource;
import basics.FactWriter;
import basics.RDFS;
import basics.Theme;
import basics.YAGO;

public class TemporalInfoboxExtractor extends Extractor{

	/** Input file */
	protected File wikipedia;
	
	public Set<Extractor> followUp() {
		return new HashSet<Extractor>(Arrays.asList(new RedirectExtractor(wikipedia, DIRTYINFOBOXFACTS,
				REDIRECTEDINFOBOXFACTS), new TypeChecker(REDIRECTEDINFOBOXFACTS, TEMPORALINFOBOXFACTS)));
	}
	public Set<Theme> input() {
		return new HashSet<Theme>(Arrays.asList(PatternHardExtractor.INFOBOXTEMPORALPATTERNS, WordnetExtractor.WORDNETWORDS,
				PatternHardExtractor.TITLEPATTERNS, HardExtractor.HARDWIREDFACTS));
	}
	/** Infobox facts, non-checked */
	public static final Theme DIRTYINFOBOXFACTS = new Theme("infoboxTemporalFactsVeryDirty",
			"Facts extracted from the Wikipedia infoboxes - still to be redirect-checked and type-checked");
	/** Redirected Infobox facts, non-checked */
	public static final Theme REDIRECTEDINFOBOXFACTS = new Theme("infoboxTemporalFactsDirty",
			"Facts extracted from the Wikipedia infoboxes with redirects resolved - still to be type-checked");
	/** Final Infobox facts */
	public static final Theme TEMPORALINFOBOXFACTS = new Theme("infoboxTemporalFacts",
			"Facts extracted from the Wikipedia infoboxes, type-checked and with redirects resolved");
	/** Infobox sources */
	public static final Theme INFOBOXSOURCES = new Theme("infoboxTemporalSources",
			"Source information for the facts extracted from the Wikipedia infoboxes");
	/** Types derived from infoboxes */
	public static final Theme INFOBOXTYPES = new Theme("infoboxTemporalTypes", "Types extracted from Wikipedia infoboxes");

	
	public Set<Theme> output() {
		return new FinalSet<Theme>(DIRTYINFOBOXFACTS, INFOBOXTYPES, INFOBOXSOURCES);
	}
	
	
	
	public void extract(Map<Theme, FactWriter> writers, Map<Theme, FactSource> input) throws Exception {
	
		FactCollection infoboxFacts = new FactCollection(input.get(PatternHardExtractor.INFOBOXTEMPORALPATTERNS));
		FactCollection hardWiredFacts = new FactCollection(input.get(HardExtractor.HARDWIREDFACTS));
		Map<String, Set<String>> patterns = InfoboxExtractor.infoboxPatterns(infoboxFacts);
		PatternList replacements = new PatternList(infoboxFacts, "<_infoboxReplace>");
		Map<String, String> combinations = infoboxFacts.asStringMap("<_infoboxCombine>");
		Map<String, String> preferredMeaning = WordnetExtractor.preferredMeanings(hardWiredFacts, new FactCollection(
				input.get(WordnetExtractor.WORDNETWORDS)));
		TitleExtractor titleExtractor = new TitleExtractor(input);

		// Extract the information
		Announce.progressStart("Extracting", 4_500_000);
		Reader in = FileUtils.getBufferedUTF8Reader(wikipedia);
		String titleEntity = null;
		while (true) {
			switch (FileLines.findIgnoreCase(in, "<title>", "{{Infobox", "{{ Infobox")) {
			case -1:
				Announce.progressDone();
				in.close();
				return;
			case 0:
				Announce.progressStep();
				titleEntity = titleExtractor.getTitleEntity(in);
				break;
			default:
				if (titleEntity == null)
					continue;
				if(titleEntity.contains("Milla"))
					System.out.println();
				String cls = FileLines.readTo(in, '}', '|').toString().trim();
				String type = preferredMeaning.get(cls);
				if (type != null) {
					write(writers, INFOBOXTYPES, new Fact(null, titleEntity, RDFS.type, type), INFOBOXSOURCES,
							titleEntity, "InfoboxExtractor: Preferred meaning of infobox type " + cls);
				}
				Map<String, Set<String>> attributes = readInfobox(in, combinations);
				for (String attribute : attributes.keySet()) {
					Set<String> relations = patterns.get(attribute);
					if (relations == null)
						continue;
					for (String relation : relations) {
						for (String value : attributes.get(attribute)) {
							extract(titleEntity, value, relation, preferredMeaning, hardWiredFacts, writers,
									replacements);
						}
					}
				}
			}
		}
	}
	
	
	/** Extracts a relation from a string */
	@SuppressWarnings("static-access")
	protected void extract(String entity, String valueString, String relation, Map<String, String> preferredMeanings,
			FactCollection factCollection, Map<Theme, FactWriter> writers, PatternList replacements) throws IOException {
		if(relation.contains(";")){
				extractMetaFact( entity,  valueString,  relation,  preferredMeanings,
						factCollection, writers, replacements);
		}
		else{
			Fact baseFact=new Fact("", "", "");
			valueString = replacements.transform(Char.decodeAmpersand(valueString));
			valueString = valueString.replace("$0", FactComponent.stripBrackets(entity));
			valueString = valueString.trim();
			if (valueString.length() == 0)
				return;

			// Check inverse
			boolean inverse;
			String cls;
			if (relation.endsWith("->")) {
				inverse = true;
				relation = Char.cutLast(Char.cutLast(relation)) + '>';
				cls = factCollection.getArg2(relation, RDFS.domain);
			} else {
				inverse = false;
				cls = factCollection.getArg2(relation, RDFS.range);
			}
			if (cls == null) {
				Announce.warning("Unknown relation to extract:", relation);
				cls = YAGO.entity;
			}

			// Get the term extractor
			TermExtractor extractor = cls.equals(RDFS.clss) ? new TermExtractor.ForClass(preferredMeanings) : TermExtractor
					.forType(cls);
			String syntaxChecker = FactComponent.asJavaString(factCollection.getArg2(cls, "<_hasTypeCheckPattern>"));

			
			// Extract all terms
			List<String> objects = extractor.extractList(valueString);
			String[] multiValues=valueString.split("\\n");
			ArrayList<List<String>> dateObjectsList= new ArrayList<>(10);
			if(multiValues.length>1){
				for(int i=0;i<multiValues.length;i++){
					dateObjectsList.add(extractor.forDate.extractList(multiValues[i]));

				}
			}
			
			for (int i =0;i<objects.size();i++) {
				String object=objects.get(i);
				// Check syntax
				if (syntaxChecker != null && !FactComponent.asJavaString(object).matches(syntaxChecker)) {
					Announce.debug("Extraction", object, "for", entity, relation, "does not match syntax check",
							syntaxChecker);
					continue;
				}
				// Check data type
				if (FactComponent.isLiteral(object)) {
					String[] value = FactComponent.literalAndDataType(object);
					if (value.length != 2 || !factCollection.isSubClassOf(value[1], cls)
							&& !(value.length == 1 && cls.equals(YAGO.string))) {
						Announce.debug("Extraction", object, "for", entity, relation, "does not match typecheck", cls);
						continue;
					}
					FactComponent.setDataType(object, cls);
				}
				if (inverse)
					write(writers, DIRTYINFOBOXFACTS, new Fact(object, relation, entity), INFOBOXSOURCES, entity,
							"InfoboxExtractor: from " + valueString);
				
				else{
					baseFact=new Fact(entity, relation, object);
					write(writers, DIRTYINFOBOXFACTS, baseFact, INFOBOXSOURCES, entity,
							"InfoboxExtractor: from " + valueString);
				}

				if(dateObjectsList.size()>0){
					List<String> dates=dateObjectsList.get(i);
						if(dates.size()>0){
							Fact metafact=baseFact.metaFact("<occursSince>", dates.get(0));
							write(writers, DIRTYINFOBOXFACTS, metafact, INFOBOXSOURCES, entity,
									"InfoboxExtractor: from " + valueString);
							if(dates.size()>1){
								metafact=baseFact.metaFact("<occursUntil>", dates.get(1));
								write(writers, DIRTYINFOBOXFACTS, metafact, INFOBOXSOURCES, entity,
										"InfoboxExtractor: from " + valueString);
							}
							
						}
					
					
				}
				if (factCollection.contains(relation, RDFS.type, YAGO.function))
					break;
			}
		}
			
		
	}

	
	
	private void extractMetaFact(String entity, String valueString, String relation, Map<String, String> preferredMeanings,
			FactCollection factCollection, Map<Theme, FactWriter> writers, PatternList replacements) throws IOException {
		Fact baseFact=new Fact("", "", "");
		valueString=valueString.replaceAll("\\t\\t", "\tNULL\t");
		String[] relations=relation.split(";");
		String values[]=valueString.split("\\t");
		
		for(int i=0;i<relations.length;i++){
			if(i>values.length-1)
				return;
			relation="<"+relations[i].replaceAll("<|>", "")+">";
			valueString= values[i];
			if(valueString.equals("NULL"))
				continue;
			valueString = replacements.transform(Char.decodeAmpersand(valueString));
			valueString = valueString.replace("$0", FactComponent.stripBrackets(entity));
			valueString = valueString.trim();
			if (valueString.length() == 0)
				return;

			// Check inverse
			boolean inverse;
			String cls;
			if (relation.endsWith("->")) {
				inverse = true;
				relation = Char.cutLast(Char.cutLast(relation)) + '>';
				cls = factCollection.getArg2(relation, RDFS.domain);
			} else {
				inverse = false;
				cls = factCollection.getArg2(relation, RDFS.range);
			}
			if (cls == null) {
				Announce.warning("Unknown relation to extract:", relation);
				cls = YAGO.entity;
			}

			// Get the term extractor
			TermExtractor extractor = cls.equals(RDFS.clss) ? new TermExtractor.ForClass(preferredMeanings) : TermExtractor
					.forType(cls);
			String syntaxChecker = FactComponent.asJavaString(factCollection.getArg2(cls, "<_hasTypeCheckPattern>"));

			// Extract all terms
			List<String> objects = extractor.extractList(valueString);
			for (String object : objects) {
				// Check syntax
				if (syntaxChecker != null && !FactComponent.asJavaString(object).matches(syntaxChecker)) {
					Announce.debug("Extraction", object, "for", entity, relation, "does not match syntax check",
							syntaxChecker);
					continue;
				}
				// Check data type
				if (FactComponent.isLiteral(object)) {
					String[] value = FactComponent.literalAndDataType(object);
					if (value.length != 2 || !factCollection.isSubClassOf(value[1], cls)
							&& !(value.length == 1 && cls.equals(YAGO.string))) {
						Announce.debug("Extraction", object, "for", entity, relation, "does not match typecheck", cls);
						continue;
					}
					FactComponent.setDataType(object, cls);
				}
				
				if (inverse)
					write(writers, DIRTYINFOBOXFACTS, new Fact(object, relation, entity), INFOBOXSOURCES, entity,
							"InfoboxExtractor: from " + valueString);
				else if(i==0){
					baseFact=new Fact(entity, relation, object);
//					baseFact.makeId();
					write(writers, DIRTYINFOBOXFACTS, baseFact, INFOBOXSOURCES, entity,
							"InfoboxExtractor: from " + valueString);
				}
				else if(!baseFact.getRelation().equals("")){
					
					Fact metafact=baseFact.metaFact(relation, object)/*new Fact(baseFact.getId(), relation, object)*/;
					write(writers, DIRTYINFOBOXFACTS, metafact, INFOBOXSOURCES, entity,
							"InfoboxExtractor: from " + valueString);
				}


				if (factCollection.contains(relation, RDFS.type, YAGO.function))
					break;
			}
		}
		
		

	}


	/** normalizes an attribute name */
	public static String normalizeAttribute(String a) {
		return (a.trim().toLowerCase().replace("_", "").replace(" ", "").replaceAll("\\d", ""));
	}
	public static String normalizeAttribute2(String a) {
		return (a.trim().toLowerCase().replace("_", "").replace(" ", ""));
	}
	/** reads an infobox */
	public static Map<String, Set<String>> readInfobox(Reader in, Map<String, String> combinations) throws IOException {
		Map<String, Set<String>> result = new TreeMap<String, Set<String>>();
		Map<String, Set<String>> resultUnNormalized = new TreeMap<String, Set<String>>();

		while (true) {
			String attribute = FileLines.readTo(in, '=', '}').toString();
			String normalizedAttribute=normalizeAttribute(attribute);
			if (normalizedAttribute.length() == 0)
				return (result);
			StringBuilder value = new StringBuilder();
			int c = InfoboxExtractor.readEnvironment(in, value);
			D.addKeyValue(result, normalizedAttribute, value.toString().trim(), TreeSet.class);
			D.addKeyValue(resultUnNormalized, normalizeAttribute2(attribute), value.toString().trim(), TreeSet.class);

			if (c == '}' || c == -1 || c == -2)
				break;
		}
		// Apply combinations
		next: for (String code : combinations.keySet()) {
			StringBuilder val = new StringBuilder();
			for (String attribute : code.split(">")) {
				int scanTo = attribute.indexOf('<');
				if (scanTo != -1) {
					val.append(attribute.substring(0, scanTo));
					String temp=attribute.substring(scanTo + 1);
					String newVal = D.pick(result.get(normalizeAttribute(temp)));
					if (newVal == null)
						continue next;
					val.append(newVal);
				} else {
					val.append(attribute);
				}
			}
			D.addKeyValue(result, normalizeAttribute(combinations.get(code)), val.toString(), TreeSet.class);
		}
		
		// Apply combinations
		next: for (String code : combinations.keySet()) {
			StringBuilder val = new StringBuilder();
			for (String attribute : code.split(">")) {
				int scanTo = attribute.indexOf('<');
				if (scanTo != -1) {
					val.append(attribute.substring(0, scanTo));
					String temp=attribute.substring(scanTo + 1);
					String newVal = D.pick(resultUnNormalized.get(normalizeAttribute2(temp)));
					if (newVal == null)
						continue next;
					val.append(newVal);
				} else {
					val.append(attribute);
				}
			}
			D.addKeyValue(resultUnNormalized, normalizeAttribute2(combinations.get(code)), val.toString(), TreeSet.class);
		}
		result.putAll(resultUnNormalized);
		return (result);
	
	}

	public TemporalInfoboxExtractor(File wikipedia) {
		this.wikipedia = wikipedia;	
	}
	
	public static void main(String[] args) {
		String valueString="erdal\tOsman\t\tMalumat";
		valueString=valueString.replaceAll("\\t\\t", "\tNULL\t");
	}

}