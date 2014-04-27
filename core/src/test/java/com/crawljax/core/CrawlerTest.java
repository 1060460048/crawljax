package com.crawljax.core;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.inject.Provider;

import java.net.MalformedURLException;
import java.net.URI;

import com.codahale.metrics.MetricRegistry;
import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.condition.browserwaiter.WaitConditionChecker;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.plugin.Plugins;
import com.crawljax.core.state.DefaultStateVertexFactory;
import com.crawljax.core.state.Eventable;
import com.crawljax.core.state.Identification;
import com.crawljax.core.state.Identification.How;
import com.crawljax.core.state.InMemoryStateFlowGraph;
import com.crawljax.core.state.StateVertex;
import com.crawljax.di.CoreModule.CandidateElementExtractorFactory;
import com.crawljax.domcomparators.DomStrippers;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * Test class for the Crawler testing.
 */
@RunWith(MockitoJUnitRunner.class)
public class CrawlerTest {

	private URI url;

	private Crawler crawler;

	@Mock
	private EmbeddedBrowser browser;

	private final CrawljaxConfiguration config = CrawljaxConfiguration.builderFor("http://localhost")
			.build();

	@Spy
	private Plugins plugins = new Plugins(config, new MetricRegistry());

	@Mock
	private Provider<CrawlSession> sessionProvider;

	@Mock
	private CrawlSession session;

	private DomStrippers domStrippers;

	@Mock
	private WaitConditionChecker waitConditionChecker;

	@Mock
	private CandidateElementExtractor extractor;

	@Mock
	private UnfiredCandidateActions candidateActionCache;

	@Mock
	private StateVertex index;

	@Mock
	private StateVertex target;

	@Mock
	private InMemoryStateFlowGraph graph;

	@Mock
	private Provider<InMemoryStateFlowGraph> graphProvider;

	@Mock
	private Eventable eventToTransferToTarget;

	@Mock
	private CandidateElement action;

	@Mock
	private ExitNotifier exitNotifier;

	private CrawlerContext context;

	@Before
	public void setup() throws MalformedURLException {
		CandidateElementExtractorFactory elementExtractor =
				mock(CandidateElementExtractorFactory.class);
		when(elementExtractor.newExtractor(browser)).thenReturn(extractor);
		url = URI.create("http://example.com");
		when(browser.getCurrentUrl()).thenReturn(url.toString());
		when(sessionProvider.get()).thenReturn(session);
		when(session.getConfig()).thenReturn(config);

		when(extractor.extract(target)).thenReturn(ImmutableList.of(action));
		domStrippers = DomStrippers.noStrippers();
		when(graphProvider.get()).thenReturn(graph);

		CrawljaxConfiguration config = Mockito.spy(CrawljaxConfiguration.builderFor(url).build());
		context =
				new CrawlerContext(browser, config, sessionProvider, exitNotifier,
						new MetricRegistry());
		crawler =
				new Crawler(context, config,
						domStrippers,
						candidateActionCache, waitConditionChecker,
						elementExtractor, graphProvider, plugins, new DefaultStateVertexFactory());

		setupStateFlowGraph();
	}

	private void setupStateFlowGraph() {
		when(index.getId()).thenReturn(1);
		when(index.getName()).thenReturn("Index");
		when(target.getId()).thenReturn(2);
		when(target.getName()).thenReturn("State 2");

		when(eventToTransferToTarget.getIdentification()).thenReturn(
				new Identification(How.name, "//DIV[@id='click]"));
		when(eventToTransferToTarget.getRelatedFrame()).thenReturn("");
		when(eventToTransferToTarget.getSourceStateVertex()).thenReturn(index);
		when(eventToTransferToTarget.getTargetStateVertex()).thenReturn(target);
		when(graph.getShortestPath(index, target)).thenReturn(
				ImmutableList.of(eventToTransferToTarget));
		when(graph.getInitialState()).thenReturn(index);
		when(session.getStateFlowGraph()).thenReturn(graph);
		when(session.getInitialState()).thenReturn(index);

		when(graph.canGoTo(index, target)).thenReturn(true);
	}

	@Test
	public void whenResetTheStateIsBackToIndex() {
		crawler.reset();
		verifyCrawlerReset(inOrder(plugins, browser));
	}

	private void verifyCrawlerReset(InOrder order) {
		order.verify(browser).goToUrl(url);
		order.verify(plugins).runOnUrlLoadPlugins(context);
	}

	@Test
	public void whenExecuteTaskTheCrawlisCompletedCorrectly() throws Exception {
		when(extractor.checkCrawlCondition()).thenReturn(true);
		when(browser.fireEventAndWait(eventToTransferToTarget)).thenReturn(true);

		crawler.execute(target);
		InOrder order =
				inOrder(extractor, browser, plugins, waitConditionChecker,
						candidateActionCache);
		verifyPathIsFollowed(order);
	}

	private void verifyPathIsFollowed(InOrder order) {
		verifyCrawlerReset(order);
		order.verify(extractor).checkCrawlCondition();
		order.verify(waitConditionChecker).wait(browser);
		order.verify(browser).closeOtherWindows();
		order.verify(plugins).runOnRevisitStatePlugins(context, target);
		order.verify(extractor).checkCrawlCondition();
		order.verify(candidateActionCache).pollActionOrNull(target);
	}


}
