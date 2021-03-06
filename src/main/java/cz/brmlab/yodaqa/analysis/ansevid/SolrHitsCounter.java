package cz.brmlab.yodaqa.analysis.ansevid;

import java.util.Collection;
import java.util.Iterator;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.brmlab.yodaqa.analysis.answer.AnswerFV;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_SolrHitsEv;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_SolrMaxScoreEv;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_SolrHitsMaxScoreEv;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerFeature;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerInfo;
import cz.brmlab.yodaqa.model.Question.Clue;
import cz.brmlab.yodaqa.provider.solr.Solr;
import cz.brmlab.yodaqa.provider.solr.SolrNamedSource;
import cz.brmlab.yodaqa.provider.solr.SolrQuerySettings;
import cz.brmlab.yodaqa.provider.solr.SolrTerm;

/**
 * For a given candidate answer, measure the number of results for
 * a search for question clues plus the answer.  We use the same
 * settings as for the baseline fulltext search. */

public class SolrHitsCounter extends JCasAnnotator_ImplBase {
	final Logger logger = LoggerFactory.getLogger(SolrHitsCounter.class);

	/** Number and baseline distance of gradually desensitivized
	 * proximity searches. Total of proximity-num optional search
	 * terms are included, covering proximity-base-dist * #of terms
	 * neighborhood. For each proximity term, the coverage is
	 * successively multiplied by proximity-base-factor; initial weight
	 * is sum of individual weights and is successively halved. */
	public static final String PARAM_PROXIMITY_NUM = "proximity-num";
	@ConfigurationParameter(name = PARAM_PROXIMITY_NUM, mandatory = false, defaultValue = "2")
	protected int proximityNum;
	public static final String PARAM_PROXIMITY_BASE_DIST = "proximity-base-dist";
	@ConfigurationParameter(name = PARAM_PROXIMITY_BASE_DIST, mandatory = false, defaultValue = "2")
	protected int proximityBaseDist;
	public static final String PARAM_PROXIMITY_BASE_FACTOR = "proximity-base-factor";
	@ConfigurationParameter(name = PARAM_PROXIMITY_BASE_FACTOR, mandatory = false, defaultValue = "3")
	protected int proximityBaseFactor;

	protected SolrQuerySettings settings = null;
	protected String srcName;
	protected Solr solr;

	protected Iterator<SolrDocument> docIter;
	protected int i;

	@Override
	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);

		/* Eew... well, for now, we just expect that only a single
		 * Solr source has been registered and grab that one,
		 * whatever its name (allows easy enwiki/guten switching). */
		this.srcName = (String) SolrNamedSource.nameSet().toArray()[0];
		this.solr = SolrNamedSource.get(srcName);

		this.settings = new SolrQuerySettings(proximityNum, proximityBaseDist, proximityBaseFactor,
				new String[]{"", "titleText"}, true /* XXX? */);
	}


	@Override
	public void process(JCas jcas) throws AnalysisEngineProcessException {
		JCas questionView, answerView;
		AnswerInfo ai;
		try {
			questionView = jcas.getView("Question");
			answerView = jcas.getView("Answer");
			ai = JCasUtil.selectSingle(answerView, AnswerInfo.class);
		} catch (Exception e) {
			return; // AnswerHitlistCAS
		}

		SolrDocumentList documents;
		try {
			Collection<Clue> clues = JCasUtil.select(questionView, Clue.class);
			Collection<SolrTerm> terms = SolrTerm.cluesToTerms(clues);

			SolrTerm answerTerm = new SolrTerm(answerView.getDocumentText(), 10 /* XXX */, true);
			terms.add(answerTerm);

			documents = solr.runQuery(terms, 10000 /* XXX */, settings, logger);
		} catch (Exception e) {
			throw new AnalysisEngineProcessException(e);
		}

		long n = documents.getNumFound();
		float s = documents.getMaxScore();
		logger.debug("{} => {}, {} => {}", answerView.getDocumentText(), n, s, n * s);

		AnswerFV fv = new AnswerFV(ai);
		fv.setFeature(AF_SolrHitsEv.class, n);
		fv.setFeature(AF_SolrMaxScoreEv.class, s);
		fv.setFeature(AF_SolrHitsMaxScoreEv.class, n * s);

		for (FeatureStructure af : ai.getFeatures().toArray())
			((AnswerFeature) af).removeFromIndexes();
		ai.removeFromIndexes();

		ai.setFeatures(fv.toFSArray(answerView));
		ai.addToIndexes();
	}
}
